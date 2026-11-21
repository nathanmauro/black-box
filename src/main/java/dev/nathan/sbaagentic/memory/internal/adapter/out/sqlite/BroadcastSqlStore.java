package dev.nathan.sbaagentic.memory.internal.adapter.out.sqlite;

import dev.nathan.sbaagentic.memory.internal.application.port.BroadcastStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * JDBC adapter for {@code project_broadcasts}. The SQL is shared by SQLite and PostgreSQL: both
 * accept an upsert whose conflict target names the partial unique index over uncleared rows.
 */
@Repository
public class BroadcastSqlStore implements BroadcastStore {

    /** Fixed width keeps text order equal to time order, which the expiry predicates rely on. */
    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private static final String COLUMNS = """
        id, project_scope, broadcast_key, kind, message, severity, source, client_session_id,
        created_at, updated_at, expires_at, follow_up_at, cleared_at, cleared_by, clear_reason
        """;

    private static final String TENTACLE = "tentacle";

    private final JdbcTemplate jdbc;

    public BroadcastSqlStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public StoredBroadcast upsert(StoredBroadcast candidate) {
        jdbc.update(
                """
                INSERT INTO project_broadcasts (
                    id, project_scope, broadcast_key, kind, message, severity, source,
                    client_session_id, created_at, updated_at, expires_at, follow_up_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (project_scope, broadcast_key) WHERE cleared_at IS NULL DO UPDATE SET
                    kind = excluded.kind,
                    message = excluded.message,
                    severity = excluded.severity,
                    source = excluded.source,
                    client_session_id = excluded.client_session_id,
                    updated_at = excluded.updated_at,
                    expires_at = excluded.expires_at,
                    follow_up_at = excluded.follow_up_at
                """,
                candidate.id(),
                candidate.projectScope(),
                candidate.key(),
                candidate.kind(),
                candidate.message(),
                candidate.severity(),
                candidate.source(),
                candidate.clientSessionId(),
                format(candidate.createdAt()),
                format(candidate.updatedAt()),
                format(candidate.expiresAt()),
                format(candidate.followUpAt()));

        return openRow(candidate.projectScope(), candidate.key())
                .orElseThrow(() -> new IllegalStateException("Broadcast upsert left no open row."));
    }

    @Override
    public Optional<StoredBroadcast> clear(
            String projectScope, String key, Instant clearedAt, String clearedBy, String reason) {
        Optional<StoredBroadcast> open = openRow(projectScope, key);
        if (open.isEmpty()) {

            return Optional.empty();
        }

        // Matching on id as well as cleared_at makes a concurrent clear a no-op, not a double write.
        int updated = jdbc.update(
                """
                UPDATE project_broadcasts
                   SET cleared_at = ?, cleared_by = ?, clear_reason = ?
                 WHERE id = ? AND cleared_at IS NULL
                """, format(clearedAt), clearedBy, reason, open.get().id());
        if (updated == 0) {

            return Optional.empty();
        }

        return byId(open.get().id());
    }

    @Override
    public Optional<StoredBroadcast> latestCleared(String projectScope, String key) {
        List<StoredBroadcast> rows = jdbc.query("SELECT " + COLUMNS + """
              FROM project_broadcasts
             WHERE project_scope = ? AND broadcast_key = ? AND cleared_at IS NOT NULL
             ORDER BY cleared_at DESC, id DESC
             LIMIT 1
            """, ROW, projectScope, key);

        return rows.stream().findFirst();
    }

    @Override
    public List<StoredBroadcast> find(
            Collection<String> projectScopes, String kind, Visibility visibility, Instant now, int limit) {
        if (projectScopes != null && projectScopes.isEmpty()) {

            return List.of();
        }
        List<String> predicates = new ArrayList<>();
        List<Object> args = new ArrayList<>();

        if (projectScopes != null) {
            predicates.add("project_scope IN (" + placeholders(projectScopes.size()) + ")");
            args.addAll(projectScopes);
        }
        if (kind != null) {
            predicates.add("kind = ?");
            args.add(kind);
        }
        switch (visibility) {
            case ACTIVE -> {
                predicates.add("cleared_at IS NULL AND (expires_at IS NULL OR expires_at > ?)");
                args.add(format(now));
            }
            case OPEN -> {
                predicates.add("cleared_at IS NULL AND (expires_at IS NULL OR expires_at > ? OR kind = ?)");
                args.add(format(now));
                args.add(TENTACLE);
            }
            case ALL -> {
                // History view: no visibility predicate.
            }
        }
        args.add(Math.max(1, limit));

        String where = predicates.isEmpty() ? "" : " WHERE " + String.join(" AND ", predicates);
        String sql =
                "SELECT " + COLUMNS + " FROM project_broadcasts" + where + " ORDER BY updated_at DESC, id DESC LIMIT ?";

        return jdbc.query(sql, ROW, args.toArray());
    }

    private Optional<StoredBroadcast> openRow(String projectScope, String key) {
        List<StoredBroadcast> rows = jdbc.query(
                "SELECT " + COLUMNS
                        + " FROM project_broadcasts WHERE project_scope = ? AND broadcast_key = ? AND cleared_at IS"
                        + " NULL",
                ROW,
                projectScope,
                key);

        return rows.stream().findFirst();
    }

    private Optional<StoredBroadcast> byId(String id) {
        List<StoredBroadcast> rows = jdbc.query("SELECT " + COLUMNS + " FROM project_broadcasts WHERE id = ?", ROW, id);

        return rows.stream().findFirst();
    }

    private static final RowMapper<StoredBroadcast> ROW = BroadcastSqlStore::mapRow;

    private static StoredBroadcast mapRow(ResultSet rs, int rowNum) throws SQLException {

        return new StoredBroadcast(
                rs.getString("id"),
                rs.getString("project_scope"),
                rs.getString("broadcast_key"),
                rs.getString("kind"),
                rs.getString("message"),
                rs.getString("severity"),
                rs.getString("source"),
                rs.getString("client_session_id"),
                parse(rs.getString("created_at")),
                parse(rs.getString("updated_at")),
                parse(rs.getString("expires_at")),
                parse(rs.getString("follow_up_at")),
                parse(rs.getString("cleared_at")),
                rs.getString("cleared_by"),
                rs.getString("clear_reason"));
    }

    static String format(Instant value) {

        return value == null ? null : TIMESTAMP.format(value);
    }

    private static Instant parse(String value) {

        return value == null ? null : Instant.parse(value);
    }

    private static String placeholders(int count) {

        return String.join(", ", Collections.nCopies(count, "?"));
    }
}
