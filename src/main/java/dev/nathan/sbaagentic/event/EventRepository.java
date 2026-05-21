package dev.nathan.sbaagentic.event;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.nathan.sbaagentic.session.AgentSession;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class EventRepository {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public EventRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public AgentSession findOrCreateSession(EventIngestRequest request, Instant observedAt, String title) {
        Optional<AgentSession> existing = findSession(request.source(), request.clientSessionId());
        if (existing.isPresent()) {
            jdbcTemplate.update("""
                    UPDATE agent_sessions
                       SET last_seen_at = ?,
                           cwd = COALESCE(?, cwd)
                     WHERE id = ?
                    """, observedAt.toString(), blankToNull(request.cwd()), existing.get().id());
            return findSessionById(existing.get().id()).orElseThrow();
        }

        String id = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                INSERT INTO agent_sessions (
                    id, source, client_session_id, title, cwd, started_at, last_seen_at, event_count
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, 0)
                """,
                id,
                request.source(),
                request.clientSessionId(),
                title,
                blankToNull(request.cwd()),
                observedAt.toString(),
                observedAt.toString());
        return findSessionById(id).orElseThrow();
    }

    public AgentEvent saveEvent(EventIngestRequest request, AgentSession session, Instant observedAt) {
        AgentEvent event = new AgentEvent(
                UUID.randomUUID().toString(),
                session.id(),
                request.source(),
                request.clientSessionId(),
                blankToNull(request.turnId()),
                request.eventType(),
                blankToNull(request.role()),
                blankToNull(request.text()),
                blankToNull(request.toolName()),
                toJson(request.toolInput()),
                toJson(request.toolOutput()),
                request.metadata() == null ? Map.of() : request.metadata(),
                observedAt);

        jdbcTemplate.update("""
                INSERT INTO agent_events (
                    id, session_id, source, client_session_id, turn_id, event_type, role, text,
                    tool_name, tool_input_json, tool_output_json, metadata_json, observed_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                event.id(),
                event.sessionId(),
                event.source(),
                event.clientSessionId(),
                event.turnId(),
                event.eventType(),
                event.role(),
                event.text(),
                event.toolName(),
                event.toolInputJson(),
                event.toolOutputJson(),
                toJson(event.metadata()),
                event.observedAt().toString());

        jdbcTemplate.update("""
                UPDATE agent_sessions
                   SET event_count = event_count + 1,
                       last_seen_at = ?
                 WHERE id = ?
                """, observedAt.toString(), session.id());

        return event;
    }

    public Optional<AgentSession> findSession(String source, String clientSessionId) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject("""
                    SELECT id, source, client_session_id, title, cwd, summary, started_at, last_seen_at, event_count
                      FROM agent_sessions
                     WHERE source = ? AND client_session_id = ?
                    """, this::mapSession, source, clientSessionId));
        }
        catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    public Optional<AgentSession> findSessionById(String id) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject("""
                    SELECT id, source, client_session_id, title, cwd, summary, started_at, last_seen_at, event_count
                      FROM agent_sessions
                     WHERE id = ?
                    """, this::mapSession, id));
        }
        catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    public List<AgentSession> recentSessions(int limit) {
        return jdbcTemplate.query("""
                SELECT id, source, client_session_id, title, cwd, summary, started_at, last_seen_at, event_count
                  FROM agent_sessions
                 ORDER BY last_seen_at DESC
                 LIMIT ?
                """, this::mapSession, limit);
    }

    public List<AgentEvent> eventsForSession(String sessionId, int limit) {
        return jdbcTemplate.query("""
                SELECT id, session_id, source, client_session_id, turn_id, event_type, role, text,
                       tool_name, tool_input_json, tool_output_json, metadata_json, observed_at
                  FROM agent_events
                 WHERE session_id = ?
                 ORDER BY observed_at DESC
                 LIMIT ?
                """, this::mapEvent, sessionId, limit);
    }

    public List<AgentEvent> searchEvents(String query, int limit) {
        String like = "%" + query.toLowerCase() + "%";
        return jdbcTemplate.query("""
                SELECT id, session_id, source, client_session_id, turn_id, event_type, role, text,
                       tool_name, tool_input_json, tool_output_json, metadata_json, observed_at
                  FROM agent_events
                 WHERE lower(coalesce(text, '')) LIKE ?
                    OR lower(coalesce(tool_name, '')) LIKE ?
                    OR lower(coalesce(event_type, '')) LIKE ?
                    OR lower(coalesce(source, '')) LIKE ?
                    OR lower(coalesce(metadata_json, '')) LIKE ?
                 ORDER BY observed_at DESC
                 LIMIT ?
                """, this::mapEvent, like, like, like, like, like, limit);
    }

    public void saveSummary(String sessionId, String summary) {
        jdbcTemplate.update("UPDATE agent_sessions SET summary = ? WHERE id = ?", summary, sessionId);
    }

    public StorageStats stats() {
        Long sessions = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM agent_sessions", Long.class);
        Long events = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM agent_events", Long.class);
        return new StorageStats(sessions == null ? 0 : sessions, events == null ? 0 : events);
    }

    private AgentSession mapSession(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new AgentSession(
                rs.getString("id"),
                rs.getString("source"),
                rs.getString("client_session_id"),
                rs.getString("title"),
                rs.getString("cwd"),
                rs.getString("summary"),
                Instant.parse(rs.getString("started_at")),
                Instant.parse(rs.getString("last_seen_at")),
                rs.getLong("event_count"));
    }

    private AgentEvent mapEvent(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new AgentEvent(
                rs.getString("id"),
                rs.getString("session_id"),
                rs.getString("source"),
                rs.getString("client_session_id"),
                rs.getString("turn_id"),
                rs.getString("event_type"),
                rs.getString("role"),
                rs.getString("text"),
                rs.getString("tool_name"),
                rs.getString("tool_input_json"),
                rs.getString("tool_output_json"),
                fromJsonMap(rs.getString("metadata_json")),
                Instant.parse(rs.getString("observed_at")));
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        }
        catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Unable to serialize event payload", ex);
        }
    }

    private Map<String, Object> fromJsonMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        }
        catch (JsonProcessingException ex) {
            return Map.of("unparsed", json);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
