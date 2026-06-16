package dev.nathan.sbaagentic.project;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.nathan.sbaagentic.session.AgentSession;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ProjectRepository {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private static final String SESSION_CANONICAL_KEY_SQL = """
            CASE
              WHEN s.cwd IS NULL OR trim(s.cwd) = '' THEN '__no_project__'
              WHEN rtrim(trim(s.cwd), '/') = '' THEN '/'
              ELSE rtrim(trim(s.cwd), '/')
            END
            """;

    private static final String STORYLINE_PREDICATE = """
            (
              lower(coalesce(e.event_type, '')) IN ('decision', 'handoff')
              OR lower(coalesce(e.metadata_json, '')) LIKE '%"kind":"decision"%'
              OR lower(coalesce(e.metadata_json, '')) LIKE '%"kind":"handoff"%'
              OR (lower(coalesce(e.role, '')) = 'assistant' AND trim(coalesce(e.text, '')) <> '')
              OR e.tool_name IS NOT NULL
              OR lower(coalesce(e.event_type, '')) LIKE '%tool%'
              OR lower(coalesce(e.event_type, '')) LIKE '%error%'
              OR lower(coalesce(e.event_type, '')) LIKE '%fail%'
            )
            """;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ProjectRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public List<ProjectSummary> summaries() {
        return jdbcTemplate.query("""
                SELECT canonical_key,
                       COUNT(*) AS session_count,
                       COALESCE(SUM(event_count), 0) AS event_count,
                       MIN(started_at) AS first_seen_at,
                       MAX(last_seen_at) AS last_seen_at
                  FROM (
                        SELECT
                          CASE
                            WHEN cwd IS NULL OR trim(cwd) = '' THEN '__no_project__'
                            WHEN rtrim(trim(cwd), '/') = '' THEN '/'
                            ELSE rtrim(trim(cwd), '/')
                          END AS canonical_key,
                          event_count,
                          started_at,
                          last_seen_at
                          FROM agent_sessions
                       ) projects
                 GROUP BY canonical_key
                 ORDER BY last_seen_at DESC
                """, this::mapSummary);
    }

    public List<AgentSession> sessionsForProject(String canonicalKey, int limit) {
        return jdbcTemplate.query("""
                SELECT s.id, s.source, s.client_session_id, s.title, s.cwd, s.summary,
                       s.started_at, s.last_seen_at, s.event_count
                  FROM agent_sessions s
                 WHERE %s = ?
                 ORDER BY s.last_seen_at DESC
                 LIMIT ?
                """.formatted(SESSION_CANONICAL_KEY_SQL), this::mapSession, canonicalKey, limit);
    }

    public List<AgentSession> sessionsForProjectByIds(String canonicalKey, List<String> sessionIds) {
        if (sessionIds == null || sessionIds.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(", ", Collections.nCopies(sessionIds.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(canonicalKey);
        args.addAll(sessionIds);
        return jdbcTemplate.query("""
                SELECT s.id, s.source, s.client_session_id, s.title, s.cwd, s.summary,
                       s.started_at, s.last_seen_at, s.event_count
                  FROM agent_sessions s
                 WHERE %s = ?
                   AND s.id IN (%s)
                 ORDER BY s.last_seen_at DESC
                """.formatted(SESSION_CANONICAL_KEY_SQL, placeholders), this::mapSession, args.toArray());
    }

    public long countTimelineBlocks(String canonicalKey) {
        Long count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                  FROM agent_events e
                  JOIN agent_sessions s ON e.session_id = s.id
                 WHERE %s = ?
                   AND %s
                """.formatted(SESSION_CANONICAL_KEY_SQL, STORYLINE_PREDICATE), Long.class, canonicalKey);
        return count == null ? 0 : count;
    }

    public List<ProjectTimelineBlock> timelineBlocks(String canonicalKey, int limit, int offset) {
        return jdbcTemplate.query("""
                SELECT e.id, e.session_id, e.source, e.client_session_id, e.turn_id, e.event_type,
                       e.role, e.text, e.tool_name, e.tool_input_json, e.tool_output_json,
                       e.metadata_json, e.observed_at, s.title AS session_title, s.cwd
                  FROM agent_events e
                  JOIN agent_sessions s ON e.session_id = s.id
                 WHERE %s = ?
                   AND %s
                 ORDER BY e.observed_at ASC
                 LIMIT ? OFFSET ?
                """.formatted(SESSION_CANONICAL_KEY_SQL, STORYLINE_PREDICATE),
                this::mapTimelineBlock, canonicalKey, limit, offset);
    }

    public List<ProjectTimelineBlock> timelineBlocksForSession(String canonicalKey, String sessionId, int limit) {
        return jdbcTemplate.query("""
                SELECT e.id, e.session_id, e.source, e.client_session_id, e.turn_id, e.event_type,
                       e.role, e.text, e.tool_name, e.tool_input_json, e.tool_output_json,
                       e.metadata_json, e.observed_at, s.title AS session_title, s.cwd
                  FROM agent_events e
                  JOIN agent_sessions s ON e.session_id = s.id
                 WHERE %s = ?
                   AND e.session_id = ?
                   AND %s
                 ORDER BY e.observed_at ASC
                 LIMIT ?
                """.formatted(SESSION_CANONICAL_KEY_SQL, STORYLINE_PREDICATE),
                this::mapTimelineBlock, canonicalKey, sessionId, limit);
    }

    private ProjectSummary mapSummary(ResultSet rs, int rowNum) throws SQLException {
        String canonicalKey = rs.getString("canonical_key");
        return new ProjectSummary(
                ProjectKeyCodec.encode(canonicalKey),
                canonicalKey,
                ProjectKeyCodec.labelFor(canonicalKey),
                rs.getLong("session_count"),
                rs.getLong("event_count"),
                0,
                Instant.parse(rs.getString("first_seen_at")),
                Instant.parse(rs.getString("last_seen_at")));
    }

    private AgentSession mapSession(ResultSet rs, int rowNum) throws SQLException {
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

    private ProjectTimelineBlock mapTimelineBlock(ResultSet rs, int rowNum) throws SQLException {
        Map<String, Object> metadata = fromJsonMap(rs.getString("metadata_json"));
        String eventType = rs.getString("event_type");
        String role = rs.getString("role");
        String text = rs.getString("text");
        String toolName = rs.getString("tool_name");
        String blockType = blockType(eventType, role, toolName, metadata);
        return new ProjectTimelineBlock(
                rs.getString("id"),
                "raw_event",
                blockType,
                headline(blockType, eventType, text, toolName, metadata),
                text,
                eventType,
                role,
                rs.getString("source"),
                rs.getString("client_session_id"),
                rs.getString("session_id"),
                rs.getString("session_title"),
                rs.getString("cwd"),
                toolName,
                rs.getString("tool_input_json"),
                rs.getString("tool_output_json"),
                metadata,
                Instant.parse(rs.getString("observed_at")));
    }

    private String blockType(String eventType, String role, String toolName, Map<String, Object> metadata) {
        String type = lower(eventType);
        String kind = lower(metadata.get("kind"));
        if ("decision".equals(kind) || "decision".equals(type)) {
            return "decision";
        }
        if ("handoff".equals(kind) || "handoff".equals(type)) {
            return "handoff";
        }
        if (type.contains("error") || type.contains("fail")) {
            return "error";
        }
        if (toolName != null && !toolName.isBlank() || type.contains("tool")) {
            return "tool";
        }
        if ("assistant".equals(lower(role))) {
            return "assistant";
        }
        return "event";
    }

    private String headline(String blockType, String eventType, String text, String toolName, Map<String, Object> metadata) {
        Object structured = switch (blockType) {
            case "decision" -> metadata.get("decision");
            case "handoff" -> metadata.get("contextSummary");
            default -> null;
        };
        if (structured instanceof String value && !value.isBlank()) {
            return firstLine(value);
        }
        if (text != null && !text.isBlank()) {
            return firstLine(text);
        }
        if (toolName != null && !toolName.isBlank()) {
            return toolName;
        }
        return eventType == null || eventType.isBlank() ? "Event" : eventType;
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

    private static String firstLine(String value) {
        int newline = value.indexOf('\n');
        return newline >= 0 ? value.substring(0, newline) : value;
    }

    private static String lower(Object value) {
        return value == null ? "" : String.valueOf(value).toLowerCase();
    }
}
