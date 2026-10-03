package dev.nathan.sbaagentic.memory.internal.adapter.out.sqlite;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nathan.sbaagentic.memory.MemoryEventReader;
import dev.nathan.sbaagentic.memory.MemoryEventReader.RecallCandidate;
import dev.nathan.sbaagentic.memory.internal.application.port.CompactEventReader;
import dev.nathan.sbaagentic.memory.internal.application.port.IdeaEventReader;
import dev.nathan.sbaagentic.query.EventQuery;
import dev.nathan.sbaagentic.query.EventQuery.Field;
import dev.nathan.sbaagentic.query.SqlInstant;
import dev.nathan.sbaagentic.recording.AgentEvent;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** Read-only SQLite projections over the recording-owned event tables. */
@Repository
public class MemorySqlQueryAdapter implements MemoryEventReader, CompactEventReader, IdeaEventReader {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private static final String SESSION_CANONICAL_CWD_SQL = """
            CASE
              WHEN s.cwd IS NULL OR trim(s.cwd) = '' THEN '__no_project__'
              WHEN rtrim(trim(s.cwd), '/') = '' THEN '/'
              ELSE rtrim(trim(s.cwd), '/')
            END
            """;

    private static final String COMPACT_PROJECTION =
            "SELECT substr(e.id,1,257) AS id, substr(e.session_id,1,257) AS session_id, "
                    + "substr(e.source,1,257) AS source, substr(e.client_session_id,1,257) AS client_session_id, "
                    + "substr(e.event_type,1,257) AS event_type, substr(e.role,1,257) AS role, "
                    + "substr(e.observed_at,1,64) AS observed_at, substr(e.text,1,601) AS text";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final boolean postgres;
    private final SqlInstant observedTime;

    public MemorySqlQueryAdapter(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper, Clock clock) {
        this(jdbcTemplate, objectMapper, clock, "sqlite");
    }

    @org.springframework.beans.factory.annotation.Autowired
    public MemorySqlQueryAdapter(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            Clock clock,
            @org.springframework.beans.factory.annotation.Value("${sba.storage.backend:sqlite}") String backend) {
        this.postgres = "postgres".equals(backend);
        this.observedTime = SqlInstant.column("e.observed_at", postgres);
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    public List<AgentEvent> searchEvents(String query, List<String> projectScopes, int limit) {

        return searchEvents(query, projectScopes, limit, false);
    }

    @Override
    public List<AgentEvent> searchEvents(String query, List<String> projectScopes, int limit, boolean humanOnly) {

        return searchProjection(
                EventQuery.parse(query),
                projectScopes,
                limit,
                clock,
                null,
                false,
                humanOnly,
                this::mapEventWithHumanText);
    }

    @Override
    public List<Candidate> searchCompact(
            EventQuery query, List<String> projectScopes, int limit, Clock requestClock, String excludeSession) {

        return searchProjection(
                query,
                projectScopes,
                limit,
                requestClock,
                excludeSession,
                true,
                false,
                (rs, row) -> new Candidate(
                        rs.getString("id"),
                        rs.getString("session_id"),
                        rs.getString("client_session_id"),
                        rs.getString("source"),
                        rs.getString("event_type"),
                        rs.getString("role"),
                        rs.getString("observed_at"),
                        rs.getString("text")));
    }

    @Override
    public List<PageRow> pageCompact(PageQuery query, int limit) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder()
                .append(COMPACT_PROJECTION)
                .append(", ")
                .append(observedTime.expression())
                .append(" AS order_key, e.id AS cursor_id\n  FROM agent_events e\n");
        if (query.projectExact() != null) {
            sql.append("  JOIN agent_sessions s ON s.id = e.session_id\n");
        }
        sql.append(" WHERE ").append(observedTime.expression()).append(" <= ?\n");
        args.add(query.untilKey());
        if (query.sessionId() != null) {
            sql.append("   AND e.session_id = ?\n");
            args.add(query.sessionId());
        }
        if (query.projectExact() != null) {
            sql.append("   AND ").append(SESSION_CANONICAL_CWD_SQL).append(" = ?\n");
            args.add(query.projectExact());
        }
        // Position functions compare bound text exactly: no wildcard, escape, or case folding.
        String position = postgres ? "strpos" : "instr";
        for (String term : query.terms()) {
            sql.append("   AND (")
                    .append(position)
                    .append("(coalesce(e.text, ''), ?) > 0 OR ")
                    .append(position)
                    .append("(coalesce(e.tool_name, ''), ?) > 0 OR ")
                    .append(position)
                    .append("(coalesce(e.metadata_json, ''), ?) > 0)\n");
            args.add(term);
            args.add(term);
            args.add(term);
        }
        if (query.beforeKey() != null && query.beforeId() != null) {
            // The scalar bound lets SQLite seek into its expression index; the tuple preserves ID ties.
            sql.append("   AND ")
                    .append(observedTime.expression())
                    .append(" <= ? AND ")
                    .append(observedTime.cursorTuple("e.id"))
                    .append(" < (?, ?)\n");
            args.add(query.beforeKey());
            args.add(query.beforeKey());
            args.add(query.beforeId());
        }
        sql.append(" ORDER BY ").append(observedTime.descending("e.id")).append("\n LIMIT ?");
        args.add(limit);

        return jdbcTemplate.query(
                sql.toString(),
                (rs, row) -> new PageRow(
                        new Candidate(
                                rs.getString("id"),
                                rs.getString("session_id"),
                                rs.getString("client_session_id"),
                                rs.getString("source"),
                                rs.getString("event_type"),
                                rs.getString("role"),
                                rs.getString("observed_at"),
                                rs.getString("text")),
                        rs.getString("order_key"),
                        rs.getString("cursor_id")),
                args.toArray());
    }

    private <T> List<T> searchProjection(
            EventQuery facets,
            List<String> projectScopes,
            int limit,
            Clock requestClock,
            String excludeSession,
            boolean compact,
            boolean humanOnly,
            RowMapper<T> mapper) {
        String projection = compact
                ? COMPACT_PROJECTION + "\n"
                : "SELECT e.id, e.session_id, e.source, e.client_session_id, e.turn_id, e.event_type, "
                        + "e.role, e.text, e.tool_name, e.tool_input_json, e.tool_output_json, e.metadata_json, "
                        + "e.observed_at, e.human_text\n";
        if (!facets.hasAnyFacet()) {
            // Facetless legacy path: free text still sweeps the wider column set (event_type and
            // source included), but terms now AND per-term instead of matching one joined phrase.
            List<Object> args = new ArrayList<>();
            StringBuilder sql = new StringBuilder()
                    .append(projection)
                    .append("  FROM agent_events e\n")
                    .append(" WHERE 1=1\n");
            if (humanOnly) {
                sql.append("   AND e.human_text IS NOT NULL\n");
            }
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
            sql.append(" ORDER BY ").append(observedTime.descending("e.id")).append("\n LIMIT ?");
            args.add(limit);

            return jdbcTemplate.query(sql.toString(), mapper, args.toArray());
        }

        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder().append(projection).append("  FROM agent_events e\n");
        boolean joinSessions = !facets.values(Field.PROJECT).isEmpty()
                || !facets.excluded(Field.PROJECT).isEmpty()
                || !facets.values(Field.PROJECT_EXACT).isEmpty()
                || !facets.excluded(Field.PROJECT_EXACT).isEmpty()
                || !facets.projectGroups().isEmpty();
        if (joinSessions) {
            sql.append("  JOIN agent_sessions s ON s.id = e.session_id\n");
        }
        sql.append(" WHERE 1=1\n");
        if (humanOnly) {
            sql.append("   AND e.human_text IS NOT NULL\n");
        }
        appendInList(sql, args, "lower(e.source)", facets.values(Field.SOURCE), false);
        appendInList(sql, args, "lower(e.event_type)", facets.values(Field.KIND), false);
        appendInList(sql, args, "lower(coalesce(e.tool_name, ''))", facets.values(Field.TOOL), false);
        appendInList(sql, args, "lower(e.source)", facets.excluded(Field.SOURCE), true);
        appendInList(sql, args, "lower(e.event_type)", facets.excluded(Field.KIND), true);
        appendInList(sql, args, "lower(coalesce(e.tool_name, ''))", facets.excluded(Field.TOOL), true);
        List<String> cwds = facets.values(Field.PROJECT);
        if (!cwds.isEmpty()) {
            sql.append("   AND (")
                    .append(String.join(
                            " OR ", Collections.nCopies(cwds.size(), "lower(coalesce(s.cwd, '')) LIKE lower(?)")))
                    .append(")\n");
            for (String cwd : cwds) {
                args.add("%" + cwd + "%");
            }
        }
        List<String> exactCwds = facets.values(Field.PROJECT_EXACT);
        if (!exactCwds.isEmpty()) {
            sql.append("   AND ")
                    .append(SESSION_CANONICAL_CWD_SQL)
                    .append(" IN (")
                    .append(String.join(", ", Collections.nCopies(exactCwds.size(), "?")))
                    .append(")\n");
            args.addAll(exactCwds);
        }
        List<String> excludedExactCwds = facets.excluded(Field.PROJECT_EXACT);
        if (!excludedExactCwds.isEmpty()) {
            sql.append("   AND ")
                    .append(SESSION_CANONICAL_CWD_SQL)
                    .append(" NOT IN (")
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
            sql.append(
                    "   AND e.session_id IN (SELECT id FROM agent_sessions WHERE id = ? OR client_session_id = ?)\n");
            args.add(ref);
            args.add(ref);
        });
        facets.sinceSpec().ifPresent(spec -> {
            sql.append("   AND ").append(observedTime.expression()).append(" >= ?\n");
            SqlInstant.bind(args, spec.resolve(requestClock));
        });
        facets.untilSpec().ifPresent(spec -> {
            sql.append("   AND ").append(observedTime.expression()).append(spec.exclusiveEnd() ? " < ?\n" : " <= ?\n");
            SqlInstant.bind(args, spec.resolve(requestClock));
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
        sql.append(" ORDER BY ").append(observedTime.descending("e.id")).append("\n LIMIT ?");
        args.add(limit);

        return jdbcTemplate.query(sql.toString(), mapper, args.toArray());
    }

    private static void appendExcludedSession(StringBuilder sql, List<Object> args, String excludeSession) {
        if (excludeSession != null) {
            sql.append(
                    " AND e.session_id NOT IN (SELECT id FROM agent_sessions WHERE id = ? OR client_session_id = ?)\n");
            args.add(excludeSession);
            args.add(excludeSession);
        }
    }

    @Override
    public List<String> distinctFieldValues(String field, String prefix, int limit) {
        FieldColumn target =
                switch (field) {
                    case "source" -> new FieldColumn("agent_events", "source");
                    case "event_type", "eventType" -> new FieldColumn("agent_events", "event_type");
                    case "tool_name", "toolName" -> new FieldColumn("agent_events", "tool_name");
                    case "client_session_id", "clientSessionId" -> new FieldColumn("agent_events", "client_session_id");
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

        return recallFiltered(eventTypes, scopeLike, since, limit, null, false, false);
    }

    @Override
    public List<AgentEvent> recallFiltered(
            List<String> eventTypes,
            String scopeLike,
            Instant since,
            int limit,
            List<String> projectScopes,
            boolean topicOnly,
            boolean includeSuperseded) {
        if (eventTypes == null || eventTypes.isEmpty()) {

            return List.of();
        }
        List<Object> args = new ArrayList<>(eventTypes);
        SqlInstant.bind(args, since);
        StringBuilder sql = recallSql(eventTypes);
        appendRecallFilters(sql, args, projectScopes, includeSuperseded);
        if (scopeLike != null) {
            if (topicOnly) {
                sql.append(" AND (lower(e.id) LIKE ? ESCAPE '\\' OR lower(coalesce(e.text, '')) LIKE ? ESCAPE '\\')");
                args.add(scopeLike);
                args.add(scopeLike);
            } else {
                sql.append(
                        " AND (lower(e.id) LIKE ? OR lower(coalesce(s.cwd, '')) LIKE ? OR lower(coalesce(e.text, '')) LIKE ?)");
                args.add(scopeLike);
                args.add(scopeLike);
                args.add(scopeLike);
            }
        }
        sql.append(" ORDER BY ").append(observedTime.descending("e.id")).append(" LIMIT ?");
        args.add(limit);

        return jdbcTemplate.query(sql.toString(), this::mapEvent, args.toArray());
    }

    @Override
    public List<RecallCandidate> recallCandidates(List<String> eventTypes, Instant since) {

        return recallCandidatesFiltered(eventTypes, since, null, false);
    }

    @Override
    public List<RecallCandidate> recallCandidatesFiltered(
            List<String> eventTypes, Instant since, List<String> projectScopes, boolean includeSuperseded) {
        if (eventTypes == null || eventTypes.isEmpty()) {

            return List.of();
        }
        List<Object> args = new ArrayList<>(eventTypes);
        SqlInstant.bind(args, since);
        StringBuilder sql = recallSql(eventTypes);
        appendRecallFilters(sql, args, projectScopes, includeSuperseded);
        sql.append(" ORDER BY ").append(observedTime.descending("e.id"));

        return jdbcTemplate.query(
                sql.toString(),
                (rs, rowNum) -> new RecallCandidate(mapEvent(rs, rowNum), rs.getString("recall_cwd")),
                args.toArray());
    }

    private StringBuilder recallSql(List<String> eventTypes) {

        return new StringBuilder("""
                SELECT e.id, e.session_id, e.source, e.client_session_id, e.turn_id, e.event_type,
                       e.role, e.text, e.tool_name, e.tool_input_json, e.tool_output_json, e.metadata_json,
                       e.observed_at, s.cwd AS recall_cwd
                  FROM agent_events e JOIN agent_sessions s ON e.session_id = s.id
                 WHERE e.event_type IN (%s) AND %s >= ?
                """.formatted(
                        String.join(", ", Collections.nCopies(eventTypes.size(), "?")), observedTime.expression()));
    }

    private void appendRecallFilters(
            StringBuilder sql, List<Object> args, List<String> projectScopes, boolean includeSuperseded) {
        if (!includeSuperseded) {
            // A replacement remains authoritative even when its event is outside this query/window.
            sql.append(" AND NOT EXISTS (SELECT 1 FROM decision_replacements r WHERE r.superseded_event_id = e.id)");
        }
        if (projectScopes != null) {
            if (projectScopes.isEmpty()) {
                sql.append(" AND 1=0");

                return;
            }
            // Captured repo is event-specific. Sessions may later move to another working directory.
            String repoValue =
                    postgres ? "e.metadata_json::jsonb ->> 'repo'" : "json_extract(e.metadata_json, '$.repo')";
            String project = "coalesce(nullif(trim(" + repoValue + "), ''), s.cwd)";
            String canonical = "CASE WHEN " + project + " IS NULL OR trim(" + project + ") = '' THEN '__no_project__' "
                    + "WHEN rtrim(trim(" + project + "), '/') = '' THEN '/' ELSE rtrim(trim(" + project + "), '/') END";
            sql.append(" AND ")
                    .append(canonical)
                    .append(" IN (")
                    .append(String.join(", ", Collections.nCopies(projectScopes.size(), "?")))
                    .append(")");
            args.addAll(projectScopes);
        }
    }

    @Override
    public Map<String, DecisionRelation> decisionRelations(List<String> eventIds) {
        if (eventIds.isEmpty()) {

            return Map.of();
        }
        String placeholders = String.join(", ", Collections.nCopies(eventIds.size(), "?"));
        List<Object> args = new ArrayList<>(eventIds);
        args.addAll(eventIds);
        Map<String, DecisionRelation> relations = new java.util.LinkedHashMap<>();
        jdbcTemplate.query(
                "SELECT superseded_event_id, superseding_event_id FROM decision_replacements "
                        + "WHERE superseded_event_id IN (" + placeholders + ") OR superseding_event_id IN ("
                        + placeholders + ")",
                rs -> {
                    String oldId = rs.getString(1);
                    String newId = rs.getString(2);
                    DecisionRelation old = relations.get(oldId);
                    relations.put(oldId, new DecisionRelation(old == null ? null : old.supersedesEventId(), newId));
                    DecisionRelation replacement = relations.get(newId);
                    relations.put(
                            newId,
                            new DecisionRelation(
                                    oldId, replacement == null ? null : replacement.supersededByEventId()));
                },
                args.toArray());

        return relations;
    }

    @Override
    public List<TypedEvent> eventsOfType(String eventType, String textPrefix, Cursor before, int limit) {
        if (eventType == null || eventType.isBlank() || limit <= 0) {

            return List.of();
        }
        List<Object> args = new ArrayList<>();
        args.add(eventType);
        StringBuilder sql = new StringBuilder("""
                SELECT e.id, e.session_id, e.source, e.client_session_id, e.turn_id, e.event_type,
                       e.role, e.text, e.tool_name, e.tool_input_json, e.tool_output_json, e.metadata_json,
                       e.observed_at, s.cwd AS typed_cwd
                  FROM agent_events e
                  JOIN agent_sessions s ON e.session_id = s.id
                 WHERE e.event_type = ?
                """);
        if (textPrefix != null && !textPrefix.isEmpty()) {
            // substr keeps the prefix literal: '[', '%', and '_' are not pattern characters here.
            sql.append("   AND substr(e.text, 1, ?) = ?\n");
            args.add(textPrefix.length());
            args.add(textPrefix);
        }
        if (before != null && before.observedAt() != null && before.id() != null) {
            // The scalar bound lets SQLite seek into its expression index; the tuple preserves ID ties.
            sql.append("   AND ")
                    .append(observedTime.expression())
                    .append(" <= ? AND ")
                    .append(observedTime.cursorTuple("e.id"))
                    .append(" < (?, ?)\n");
            SqlInstant.bind(args, before.observedAt());
            SqlInstant.bind(args, before.observedAt());
            args.add(before.id());
        }
        sql.append(" ORDER BY ").append(observedTime.descending("e.id")).append("\n LIMIT ?");
        args.add(limit);

        return jdbcTemplate.query(
                sql.toString(),
                (rs, rowNum) -> new TypedEvent(mapEvent(rs, rowNum), rs.getString("typed_cwd")),
                args.toArray());
    }

    private static void appendInList(
            StringBuilder sql, List<Object> args, String columnExpr, List<String> values, boolean negated) {
        if (values.isEmpty()) {

            return;
        }
        sql.append("   AND ")
                .append(columnExpr)
                .append(negated ? " NOT IN (" : " IN (")
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
        sql.append("   AND ")
                .append(SESSION_CANONICAL_CWD_SQL)
                .append(" IN (")
                .append(String.join(", ", Collections.nCopies(scopes.size(), "?")))
                .append(")\n");
        args.addAll(scopes);
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

    private AgentEvent mapEventWithHumanText(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        AgentEvent event = mapEvent(rs, rowNum);

        return new AgentEvent(
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
                event.metadata(),
                event.observedAt(),
                rs.getString("human_text"));
    }

    private Map<String, Object> fromJsonMap(String json) {
        if (json == null || json.isBlank()) {

            return Map.of();
        }
        try {

            return objectMapper.readValue(json, MAP_TYPE);
        } catch (JsonProcessingException ex) {

            return Map.of("unparsed", json);
        }
    }

    private record FieldColumn(String table, String column) {}
}
