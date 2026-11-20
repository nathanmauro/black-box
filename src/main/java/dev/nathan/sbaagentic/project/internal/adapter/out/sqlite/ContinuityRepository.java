package dev.nathan.sbaagentic.project.internal.adapter.out.sqlite;

import dev.nathan.sbaagentic.project.internal.application.port.ContinuityStore;
import dev.nathan.sbaagentic.query.SqlInstant;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** SELECT-only reads for continuity diagnostics and the braid-graph export. */
@Repository
@DependsOn("meldSchemaMigration")
public class ContinuityRepository implements ContinuityStore {

    /** Stored bodies are read up to this many characters; excerpts are far shorter. */
    private static final int TEXT_PREFIX_CHARS = 4000;

    /** Ids bound per IN query, far below the SQLite and PostgreSQL bind-variable limits. */
    private static final int ID_BATCH = 500;

    private static final Comparator<BraidRow> NEWEST_FIRST = Comparator.comparing(BraidRow::createdAt)
            .thenComparing(BraidRow::id)
            .reversed();

    private final JdbcTemplate jdbc;
    private final boolean postgres;

    public ContinuityRepository(JdbcTemplate jdbc, @Value("${sba.storage.backend:sqlite}") String backend) {
        this.jdbc = jdbc;
        this.postgres = ProjectSqlDialect.from(backend) == ProjectSqlDialect.POSTGRES;
    }

    @Override
    public List<CaptureRow> captures(Collection<String> eventTypes, int limit) {
        if (eventTypes.isEmpty() || limit <= 0) {

            return List.of();
        }
        SqlInstant observed = SqlInstant.column("e.observed_at", postgres);
        List<Object> args = new ArrayList<>(eventTypes);
        args.add(limit);
        String sql = """
                SELECT e.id, e.session_id, e.event_type, e.metadata_json,
                       substr(e.text, 1, %d) AS text_prefix, e.observed_at, s.cwd
                  FROM agent_events e
                  LEFT JOIN agent_sessions s ON s.id = e.session_id
                 WHERE e.event_type IN (%s)
                 ORDER BY %s
                 LIMIT ?
                """.formatted(TEXT_PREFIX_CHARS, placeholders(eventTypes.size()), observed.descending("e.id"));

        return jdbc.query(
                sql,
                (rs, n) -> new CaptureRow(
                        rs.getString("id"),
                        rs.getString("session_id"),
                        rs.getString("event_type"),
                        rs.getString("metadata_json"),
                        rs.getString("text_prefix"),
                        Instant.parse(rs.getString("observed_at")),
                        rs.getString("cwd")),
                args.toArray());
    }

    @Override
    public long countCaptures(Collection<String> eventTypes) {
        if (eventTypes.isEmpty()) {

            return 0;
        }
        String sql = "SELECT count(*) FROM agent_events WHERE event_type IN (" + placeholders(eventTypes.size()) + ")";
        Long count = jdbc.queryForObject(sql, Long.class, eventTypes.toArray());

        return count == null ? 0 : count;
    }

    @Override
    public List<SessionRow> sessions(Collection<String> ids) {
        List<String> distinct = ids.stream().distinct().toList();
        List<SessionRow> rows = new ArrayList<>(distinct.size());
        for (int start = 0; start < distinct.size(); start += ID_BATCH) {
            List<String> batch = distinct.subList(start, Math.min(distinct.size(), start + ID_BATCH));
            String sql = "SELECT id, source, title, cwd, started_at FROM agent_sessions WHERE id IN ("
                    + placeholders(batch.size()) + ")";
            rows.addAll(jdbc.query(
                    sql,
                    (rs, n) -> new SessionRow(
                            rs.getString("id"),
                            rs.getString("source"),
                            rs.getString("title"),
                            rs.getString("cwd"),
                            Instant.parse(rs.getString("started_at"))),
                    batch.toArray()));
        }

        return rows;
    }

    @Override
    public List<BraidRow> braids(int limit) {
        if (limit <= 0) {

            return List.of();
        }
        SqlInstant created = SqlInstant.column("created_at", postgres);
        String sql = """
                SELECT m.id, m.project_key, m.title, m.created_at,
                       i.session_id, i.input_order, s.id AS existing_id
                  FROM session_melds m
                  LEFT JOIN session_meld_inputs i ON i.meld_id = m.id
                  LEFT JOIN agent_sessions s ON s.id = i.session_id
                 WHERE m.id IN (
                       SELECT id FROM session_melds
                        WHERE artifact_kind = 'braid'
                        ORDER BY %s
                        LIMIT ?)
                 ORDER BY m.id, i.input_order, i.session_id
                """.formatted(created.descending("id"));
        Map<String, BraidRow> grouped = new LinkedHashMap<>();
        Map<String, List<BraidInput>> inputs = new LinkedHashMap<>();
        jdbc.query(
                sql,
                rs -> {
                    String id = rs.getString("id");
                    if (!grouped.containsKey(id)) {
                        Instant createdAt = Instant.parse(rs.getString("created_at"));
                        grouped.put(
                                id,
                                new BraidRow(
                                        id, rs.getString("project_key"), rs.getString("title"), createdAt, List.of()));
                        inputs.put(id, new ArrayList<>());
                    }
                    String sessionId = rs.getString("session_id");
                    if (sessionId != null) {
                        boolean exists = rs.getString("existing_id") != null;
                        inputs.get(id).add(new BraidInput(sessionId, rs.getInt("input_order"), exists));
                    }
                },
                limit);

        return grouped.values().stream()
                .map(row -> new BraidRow(
                        row.id(), row.projectKey(), row.title(), row.createdAt(), List.copyOf(inputs.get(row.id()))))
                .sorted(NEWEST_FIRST)
                .toList();
    }

    @Override
    public long countBraids() {
        Long count =
                jdbc.queryForObject("SELECT count(*) FROM session_melds WHERE artifact_kind = 'braid'", Long.class);

        return count == null ? 0 : count;
    }

    private static String placeholders(int count) {

        return String.join(",", Collections.nCopies(count, "?"));
    }
}
