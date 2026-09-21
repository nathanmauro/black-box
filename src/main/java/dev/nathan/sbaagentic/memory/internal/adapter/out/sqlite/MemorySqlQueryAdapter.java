package dev.nathan.sbaagentic.memory.internal.adapter.out.sqlite;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.nathan.sbaagentic.memory.MemoryEventReader;
import dev.nathan.sbaagentic.memory.MemoryEventReader.RecallCandidate;
import dev.nathan.sbaagentic.recording.AgentEvent;
import dev.nathan.sbaagentic.query.EventQuery;
import dev.nathan.sbaagentic.query.EventQuery.Field;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import dev.nathan.sbaagentic.memory.internal.application.port.CompactEventReader;
import org.springframework.stereotype.Repository;

/** Read-only SQLite projections over the recording-owned event tables. */
@Repository
public class MemorySqlQueryAdapter implements MemoryEventReader, CompactEventReader {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private static final String SESSION_CANONICAL_CWD_SQL = """
            CASE
              WHEN s.cwd IS NULL OR trim(s.cwd) = '' THEN '__no_project__'
              WHEN rtrim(trim(s.cwd), '/') = '' THEN '/'
              ELSE rtrim(trim(s.cwd), '/')
            END
            """;

    // Canonical timestamps are UTC ISO strings with variable fractional precision. Padding avoids
    // ordering a later fractional instant before its whole-second boundary ('.' sorts before 'Z').
    private static final String COMPACT_TIME_SQL = "(substr(e.observed_at,1,19) || '.' || CASE "
            + "WHEN substr(e.observed_at,20,1) = '.' THEN "
            + "substr(substr(e.observed_at,21,length(e.observed_at)-21) || '000000000',1,9) "
            + "ELSE '000000000' END || 'Z')";
    private static final java.time.format.DateTimeFormatter COMPACT_TIME =
            new java.time.format.DateTimeFormatterBuilder().appendInstant(9).toFormatter();

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public MemorySqlQueryAdapter(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    public List<AgentEvent> searchEvents(String query, List<String> projectScopes, int limit) {
        return searchProjection(EventQuery.parse(query), projectScopes, limit, clock, null, false, this::mapEvent);
    }

    @Override
    public List<Candidate> searchCompact(EventQuery query, List<String> projectScopes, int limit,
            Clock requestClock, String excludeSession) {
        return searchProjection(query, projectScopes, limit, requestClock, excludeSession, true,
                (rs, row) -> new Candidate(rs.getString("id"), rs.getString("session_id"),
                        rs.getString("client_session_id"), rs.getString("source"), rs.getString("event_type"),
                        rs.getString("role"), rs.getString("observed_at"), rs.getString("text")));
    }

    private <T> List<T> searchProjection(EventQuery facets, List<String> projectScopes, int limit,
            Clock requestClock, String excludeSession, boolean compact, RowMapper<T> mapper) {
        String projection = compact
                ? "SELECT substr(e.id,1,257) AS id, substr(e.session_id,1,257) AS session_id, "
                    + "substr(e.source,1,257) AS source, substr(e.client_session_id,1,257) AS client_session_id, "
                    + "substr(e.event_type,1,257) AS event_type, substr(e.role,1,257) AS role, "
                    + "substr(e.observed_at,1,64) AS observed_at, substr(e.text,1,601) AS text\n"
                : "SELECT e.id, e.session_id, e.source, e.client_session_id, e.turn_id, e.event_type, "
                    + "e.role, e.text, e.tool_name, e.tool_input_json, e.tool_output_json, e.metadata_json, "
                    + "e.observed_at\n";
        if (!facets.hasAnyFacet()) {
            // Facetless legacy path: free text still sweeps the wider column set (event_type and
            // source included), but terms now AND per-term instead of matching one joined phrase.
            List<Object> args = new ArrayList<>();
            StringBuilder sql = new StringBuilder()
                    .append(projection)
                    .append("  FROM agent_events e\n")
                    .append(" WHERE 1=1\n");
            for (String term : facets.freeTerms()) {
                String like = "%" + term.toLowerCase() + "%";
                sql.append("   AND (lower(coalesce(text, '')) LIKE ?")
                        .append(" OR lower(coalesce(tool_name, '')) LIKE ?")
                        .append(" OR lower(coalesce(event_type, '')) LIKE ?")
                        .append(" OR lower(coalesce(source, '')) LIKE ?")
                        .append(" OR lower(coalesce(metadata_json, '')) LIKE ?)\n");
                for (int i = 0; i < 5; i++) {
                    args.add(like);
                }
            }
            appendExcludedSession(sql, args, excludeSession);
            sql.append(compact ? " ORDER BY " + COMPACT_TIME_SQL + " DESC, e.id DESC\n LIMIT ?" : " ORDER BY observed_at DESC\n LIMIT ?");
            args.add(limit);
            return jdbcTemplate.query(sql.toString(), mapper, args.toArray());
        }

        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder()
                .append(projection)
                .append("  FROM agent_events e\n");
        boolean joinSessions = !facets.values(Field.PROJECT).isEmpty()
                || !facets.excluded(Field.PROJECT).isEmpty()
                || !facets.values(Field.PROJECT_EXACT).isEmpty()
                || !facets.excluded(Field.PROJECT_EXACT).isEmpty()
                || !facets.projectGroups().isEmpty();
        if (joinSessions) {
            sql.append("  JOIN agent_sessions s ON s.id = e.session_id\n");
        }
        sql.append(" WHERE 1=1\n");
        appendInList(sql, args, "lower(e.source)", facets.values(Field.SOURCE), false);
        appendInList(sql, args, "lower(e.event_type)", facets.values(Field.KIND), false);
        appendInList(sql, args, "lower(coalesce(e.tool_name, ''))", facets.values(Field.TOOL), false);
        appendInList(sql, args, "lower(e.source)", facets.excluded(Field.SOURCE), true);
        appendInList(sql, args, "lower(e.event_type)", facets.excluded(Field.KIND), true);
        appendInList(sql, args, "lower(coalesce(e.tool_name, ''))", facets.excluded(Field.TOOL), true);
        List<String> cwds = facets.values(Field.PROJECT);
        if (!cwds.isEmpty()) {
            sql.append("   AND (")
                    .append(String.join(" OR ",
                            Collections.nCopies(cwds.size(), "lower(coalesce(s.cwd, '')) LIKE lower(?)")))
                    .append(")\n");
            for (String cwd : cwds) {
                args.add("%" + cwd + "%");
            }
        }
        List<String> exactCwds = facets.values(Field.PROJECT_EXACT);
        if (!exactCwds.isEmpty()) {
            sql.append("   AND ").append(SESSION_CANONICAL_CWD_SQL).append(" IN (")
                    .append(String.join(", ", Collections.nCopies(exactCwds.size(), "?")))
                    .append(")\n");
            args.addAll(exactCwds);
        }
        List<String> excludedExactCwds = facets.excluded(Field.PROJECT_EXACT);
        if (!excludedExactCwds.isEmpty()) {
            sql.append("   AND ").append(SESSION_CANONICAL_CWD_SQL).append(" NOT IN (")
                    .append(String.join(", ", Collections.nCopies(excludedExactCwds.size(), "?")))
                    .append(")\n");
            args.addAll(excludedExactCwds);
        }
        appendProjectGroup(sql, args, facets.projectGroups(), projectScopes);
        for (String cwd : facets.excluded(Field.PROJECT)) {
            sql.append("   AND lower(coalesce(s.cwd, '')) NOT LIKE lower(?)\n");
            args.add("%" + cwd + "%");
        }
        facets.sessionRef().ifPresent(ref -> {
            sql.append("   AND e.session_id IN (SELECT id FROM agent_sessions WHERE id = ? OR client_session_id = ?)\n");
            args.add(ref);
            args.add(ref);
        });
        facets.sinceSpec().ifPresent(spec -> {
            sql.append("   AND ").append(compact ? COMPACT_TIME_SQL : "e.observed_at").append(" >= ?\n");
            args.add(compact ? COMPACT_TIME.format(spec.resolve(requestClock)) : spec.resolve(requestClock).toString());
        });
        facets.untilSpec().ifPresent(spec -> {
            sql.append("   AND ").append(compact ? COMPACT_TIME_SQL : "e.observed_at")
                    .append(spec.exclusiveEnd() ? " < ?\n" : " <= ?\n");
            args.add(compact ? COMPACT_TIME.format(spec.resolve(requestClock)) : spec.resolve(requestClock).toString());
        });
        // is:all is deliberately a no-op here: search has no meaningful filter to disable.
        for (String term : facets.freeTerms()) {
            String like = "%" + term.toLowerCase() + "%";
            sql.append("   AND (lower(coalesce(e.text, '')) LIKE ?")
                    .append(" OR lower(coalesce(e.tool_name, '')) LIKE ?")
                    .append(" OR lower(coalesce(e.metadata_json, '')) LIKE ?)\n");
            args.add(like);
            args.add(like);
            args.add(like);
        }
        appendExcludedSession(sql, args, excludeSession);
        sql.append(compact ? " ORDER BY " + COMPACT_TIME_SQL + " DESC, e.id DESC\n LIMIT ?" : " ORDER BY e.observed_at DESC\n LIMIT ?");
        args.add(limit);
        return jdbcTemplate.query(sql.toString(), mapper, args.toArray());
    }

    private static void appendExcludedSession(StringBuilder sql, List<Object> args, String excludeSession) {
        if (excludeSession != null) {
            sql.append(" AND e.session_id NOT IN (SELECT id FROM agent_sessions WHERE id = ? OR client_session_id = ?)\n");
            args.add(excludeSession);
            args.add(excludeSession);
        }
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

    @Override
    public List<RecallCandidate> recallCandidates(List<String> eventTypes, Instant since) {
        if (eventTypes == null || eventTypes.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(", ", Collections.nCopies(eventTypes.size(), "?"));
        List<Object> args = new ArrayList<>(eventTypes);
        args.add(since.toString());
        String sql = """
                SELECT e.id, e.session_id, e.source, e.client_session_id, e.turn_id, e.event_type,
                       e.role, e.text, e.tool_name, e.tool_input_json, e.tool_output_json, e.metadata_json,
                       e.observed_at, s.cwd AS recall_cwd
                  FROM agent_events e
                  JOIN agent_sessions s ON e.session_id = s.id
                 WHERE e.event_type IN (%s)
                   AND e.observed_at >= ?
                 ORDER BY e.observed_at DESC
                """.formatted(placeholders);
        return jdbcTemplate.query(sql, (rs, rowNum) -> new RecallCandidate(
                mapEvent(rs, rowNum),
                rs.getString("recall_cwd")), args.toArray());
    }

    private static void appendInList(
            StringBuilder sql, List<Object> args, String columnExpr, List<String> values, boolean negated) {
        if (values.isEmpty()) {
            return;
        }
        sql.append("   AND ").append(columnExpr).append(negated ? " NOT IN (" : " IN (")
                .append(String.join(", ", Collections.nCopies(values.size(), "lower(?)")))
                .append(")\n");
        args.addAll(values);
    }

    private static void appendProjectGroup(
            StringBuilder sql, List<Object> args, List<String> groups, List<String> projectScopes) {
        if (groups.isEmpty()) {
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
