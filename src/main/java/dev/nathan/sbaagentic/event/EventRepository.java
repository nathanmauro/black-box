package dev.nathan.sbaagentic.event;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.nathan.sbaagentic.session.AgentSession;
import dev.nathan.sbaagentic.session.TitleRank;

import jakarta.annotation.PostConstruct;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

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

    /**
     * Idempotent migration for databases created before title ranking existed.
     * {@code schema.sql} runs on every startup, and SQLite has no
     * {@code ADD COLUMN IF NOT EXISTS}, so the column add lives here. Fresh
     * databases already get {@code title_rank} from the CREATE statement and
     * skip this entirely.
     */
    @PostConstruct
    void ensureSchema() {
        // WAL lets concurrent agents (a Claude hook and a Codex hook firing at once) read while one
        // writes, instead of serializing behind a global lock. It is a persistent property of the
        // database file, so setting it once per boot is enough; busy_timeout (set per connection in
        // application.yml) keeps the rare writer-vs-writer contention from surfacing as an error.
        jdbcTemplate.execute("PRAGMA journal_mode=WAL");
        List<Map<String, Object>> columns = jdbcTemplate.queryForList("PRAGMA table_info(agent_sessions)");
        if (columns.isEmpty()) {
            return;
        }
        boolean hasTitleRank = columns.stream()
                .anyMatch(column -> "title_rank".equalsIgnoreCase(String.valueOf(column.get("name"))));
        if (!hasTitleRank) {
            jdbcTemplate.execute("ALTER TABLE agent_sessions ADD COLUMN title_rank INTEGER NOT NULL DEFAULT " + TitleRank.FALLBACK);
            // Existing titles predate ranking; protect them so only an AI retitle replaces them.
            jdbcTemplate.update("UPDATE agent_sessions SET title_rank = ?", TitleRank.LEGACY);
        }
    }

    /**
     * Persists an event together with its session as one atomic unit. The session upsert, the event
     * insert, and the event-count bump either all land or none do, so a crash mid-ingest can never
     * leave a session whose count disagrees with its events.
     */
    @Transactional
    public Persisted persistEvent(EventIngestRequest request, Instant observedAt, String title, int titleRank) {
        AgentSession session = findOrCreateSession(request, observedAt, title, titleRank);
        AgentEvent event = saveEvent(request, session, observedAt);
        AgentSession updated = findSessionById(session.id()).orElse(session);
        return new Persisted(updated, event);
    }

    /** A persisted event and the session snapshot it landed in. */
    public record Persisted(AgentSession session, AgentEvent event) {
    }

    public AgentSession findOrCreateSession(EventIngestRequest request, Instant observedAt, String title, int titleRank) {
        // One atomic upsert: insert a fresh session, or — when (source, client_session_id) already
        // exists — bump its activity and upgrade the title only if this event carries a strictly
        // higher-ranked one. ON CONFLICT makes find-or-create race-free: two concurrent first events
        // for the same session can't double-insert or trip the UNIQUE constraint. started_at and
        // event_count are set only on insert, so an existing session keeps its origin and count.
        String id = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                INSERT INTO agent_sessions (
                    id, source, client_session_id, title, title_rank, cwd, started_at, last_seen_at, event_count
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0)
                ON CONFLICT (source, client_session_id) DO UPDATE SET
                    last_seen_at = excluded.last_seen_at,
                    cwd = COALESCE(excluded.cwd, agent_sessions.cwd),
                    title = CASE WHEN excluded.title_rank > agent_sessions.title_rank
                                 THEN excluded.title ELSE agent_sessions.title END,
                    title_rank = CASE WHEN excluded.title_rank > agent_sessions.title_rank
                                      THEN excluded.title_rank ELSE agent_sessions.title_rank END
                """,
                id,
                request.source(),
                request.clientSessionId(),
                title,
                titleRank,
                blankToNull(request.cwd()),
                observedAt.toString(),
                observedAt.toString());
        return findSession(request.source(), request.clientSessionId()).orElseThrow();
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

    /**
     * Reads prior intent back out for {@link dev.nathan.sbaagentic.context.ContextService}. Filters
     * to the given event types, bounds the window by {@code since}, and — when {@code scopeLike} is
     * present — matches it against the session's working directory (recall by where you are working)
     * or the event text (recall by what you are working on). Newest first.
     */
    public List<AgentEvent> recall(List<String> eventTypes, String scopeLike, Instant since, int limit) {
        if (eventTypes == null || eventTypes.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(", ", Collections.nCopies(eventTypes.size(), "?"));
        List<Object> args = new ArrayList<>(eventTypes);
        args.add(since.toString());
        StringBuilder sql = new StringBuilder()
                .append("SELECT e.id, e.session_id, e.source, e.client_session_id, e.turn_id, e.event_type, ")
                .append("e.role, e.text, e.tool_name, e.tool_input_json, e.tool_output_json, e.metadata_json, ")
                .append("e.observed_at\n")
                .append("  FROM agent_events e\n")
                .append("  JOIN agent_sessions s ON e.session_id = s.id\n")
                .append(" WHERE e.event_type IN (").append(placeholders).append(")\n")
                .append("   AND e.observed_at >= ?");
        if (scopeLike != null) {
            sql.append("\n   AND (lower(coalesce(s.cwd, '')) LIKE ? OR lower(coalesce(e.text, '')) LIKE ?)");
            args.add(scopeLike);
            args.add(scopeLike);
        }
        sql.append("\n ORDER BY e.observed_at DESC\n LIMIT ?");
        args.add(limit);
        return jdbcTemplate.query(sql.toString(), this::mapEvent, args.toArray());
    }

    /**
     * Writes a session's summary and its AI-derived title atomically, so a half-applied summarize
     * can never leave a session summarized but still wearing its weak ingest-time title.
     */
    @Transactional
    public void saveSummaryAndTitle(String sessionId, String summary, String title, int titleRank) {
        jdbcTemplate.update("UPDATE agent_sessions SET summary = ? WHERE id = ?", summary, sessionId);
        jdbcTemplate.update("UPDATE agent_sessions SET title = ?, title_rank = ? WHERE id = ?",
                title, titleRank, sessionId);
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
