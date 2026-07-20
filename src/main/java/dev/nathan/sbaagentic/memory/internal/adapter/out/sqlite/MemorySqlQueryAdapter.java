package dev.nathan.sbaagentic.memory.internal.adapter.out.sqlite;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.nathan.sbaagentic.memory.MemoryEventReader;
import dev.nathan.sbaagentic.recording.AgentEvent;
import dev.nathan.sbaagentic.memory.QueryFacets;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Read-only SQLite projections over the recording-owned event tables. */
@Repository
public class MemorySqlQueryAdapter implements MemoryEventReader {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private static final String SESSION_CANONICAL_CWD_SQL = """
            CASE
              WHEN s.cwd IS NULL OR trim(s.cwd) = '' THEN '__no_project__'
              WHEN rtrim(trim(s.cwd), '/') = '' THEN '/'
              ELSE rtrim(trim(s.cwd), '/')
            END
            """;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public MemorySqlQueryAdapter(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<AgentEvent> searchEvents(String query, List<String> projectScopes, int limit) {
        QueryFacets facets = QueryFacets.parse(query);
        if (!facets.hasAnyFacet()) {
            String like = "%" + (query == null ? "" : query).toLowerCase() + "%";
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

        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder()
                .append("SELECT e.id, e.session_id, e.source, e.client_session_id, e.turn_id, e.event_type, ")
                .append("e.role, e.text, e.tool_name, e.tool_input_json, e.tool_output_json, e.metadata_json, ")
                .append("e.observed_at\n")
                .append("  FROM agent_events e\n");
        boolean joinSessions = facets.cwd() != null
                || facets.excludedCwd() != null
                || facets.exactCwd() != null
                || facets.groupCwd() != null;
        if (joinSessions) {
            sql.append("  JOIN agent_sessions s ON s.id = e.session_id\n");
        }
        sql.append(" WHERE 1=1\n");
        appendKeywordFacets(sql, args, facets);
        if (facets.cwd() != null) {
            sql.append("   AND lower(coalesce(s.cwd, '')) LIKE lower(?)\n");
            args.add("%" + facets.cwd() + "%");
        }
        if (facets.exactCwd() != null) {
            sql.append("   AND ").append(SESSION_CANONICAL_CWD_SQL).append(" = ?\n");
            args.add(facets.exactCwd());
        }
        appendProjectGroup(sql, args, facets.groupCwd(), projectScopes);
        if (facets.excludedCwd() != null) {
            sql.append("   AND lower(coalesce(s.cwd, '')) NOT LIKE lower(?)\n");
            args.add("%" + facets.excludedCwd() + "%");
        }
        appendFreeText(sql, args, facets.freeTextPhrase());
        sql.append(" ORDER BY e.observed_at DESC\n LIMIT ?");
        args.add(limit);
        return jdbcTemplate.query(sql.toString(), this::mapEvent, args.toArray());
    }

    @Override
    public List<String> distinctFieldValues(String field, String prefix, int limit) {
        FieldColumn target = switch (field) {
            case "source" -> new FieldColumn("agent_events", "source");
            case "event_type", "eventType" -> new FieldColumn("agent_events", "event_type");
            case "tool_name", "toolName" -> new FieldColumn("agent_events", "tool_name");
            case "client_session_id", "clientSessionId" ->
                    new FieldColumn("agent_events", "client_session_id");
            case "cwd" -> new FieldColumn("agent_sessions", "cwd");
            default -> null;
        };
        if (target == null) {
            return List.of();
        }
        int safeLimit = Math.max(1, Math.min(limit, 50));
        String like = (prefix == null ? "" : prefix) + "%";
        String sql = "SELECT DISTINCT " + target.column() + " AS v"
                + "   FROM " + target.table()
                + "  WHERE " + target.column() + " IS NOT NULL AND " + target.column() + " <> ''"
                + "    AND lower(" + target.column() + ") LIKE lower(?)"
                + "  ORDER BY " + target.column()
                + "  LIMIT ?";
        return jdbcTemplate.query(sql, (rs, rowNum) -> rs.getString("v"), like, safeLimit);
    }

    @Override
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
            sql.append("\n   AND (lower(e.id) LIKE ? OR lower(coalesce(s.cwd, '')) LIKE ?"
                    + " OR lower(coalesce(e.text, '')) LIKE ?)");
            args.add(scopeLike);
            args.add(scopeLike);
            args.add(scopeLike);
        }
        sql.append("\n ORDER BY e.observed_at DESC\n LIMIT ?");
        args.add(limit);
        return jdbcTemplate.query(sql.toString(), this::mapEvent, args.toArray());
    }

    private static void appendKeywordFacets(StringBuilder sql, List<Object> args, QueryFacets facets) {
        if (facets.source() != null) {
            sql.append("   AND lower(e.source) = lower(?)\n");
            args.add(facets.source());
        }
        if (facets.eventType() != null) {
            sql.append("   AND lower(e.event_type) = lower(?)\n");
            args.add(facets.eventType());
        }
        if (facets.toolName() != null) {
            sql.append("   AND lower(coalesce(e.tool_name, '')) = lower(?)\n");
            args.add(facets.toolName());
        }
        if (facets.excludedSource() != null) {
            sql.append("   AND lower(e.source) <> lower(?)\n");
            args.add(facets.excludedSource());
        }
        if (facets.excludedEventType() != null) {
            sql.append("   AND lower(e.event_type) <> lower(?)\n");
            args.add(facets.excludedEventType());
        }
        if (facets.excludedToolName() != null) {
            sql.append("   AND lower(coalesce(e.tool_name, '')) <> lower(?)\n");
            args.add(facets.excludedToolName());
        }
    }

    private static void appendProjectGroup(
            StringBuilder sql, List<Object> args, String group, List<String> projectScopes) {
        if (group == null) {
            return;
        }
        List<String> scopes = projectScopes == null ? List.of() : projectScopes;
        if (scopes.isEmpty()) {
            sql.append("   AND 1=0\n");
            return;
        }
        sql.append("   AND ").append(SESSION_CANONICAL_CWD_SQL).append(" IN (")
                .append(String.join(", ", Collections.nCopies(scopes.size(), "?")))
                .append(")\n");
        args.addAll(scopes);
    }

    private static void appendFreeText(StringBuilder sql, List<Object> args, String freePhrase) {
        if (freePhrase.isBlank()) {
            return;
        }
        String like = "%" + freePhrase.toLowerCase() + "%";
        sql.append("   AND (lower(coalesce(e.text, '')) LIKE ?")
                .append(" OR lower(coalesce(e.tool_name, '')) LIKE ?")
                .append(" OR lower(coalesce(e.metadata_json, '')) LIKE ?)\n");
        args.add(like);
        args.add(like);
        args.add(like);
    }

    private AgentEvent mapEvent(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new AgentEvent(
                rs.getString("id"), rs.getString("session_id"), rs.getString("source"),
                rs.getString("client_session_id"), rs.getString("turn_id"),
                rs.getString("event_type"), rs.getString("role"), rs.getString("text"),
                rs.getString("tool_name"), rs.getString("tool_input_json"),
                rs.getString("tool_output_json"), fromJsonMap(rs.getString("metadata_json")),
                Instant.parse(rs.getString("observed_at")));
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

    private record FieldColumn(String table, String column) {
    }
}
