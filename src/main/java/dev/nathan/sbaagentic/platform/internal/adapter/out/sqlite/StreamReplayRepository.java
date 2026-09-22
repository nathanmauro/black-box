package dev.nathan.sbaagentic.platform.internal.adapter.out.sqlite;

import dev.nathan.sbaagentic.platform.internal.application.StreamEventSnapshot;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class StreamReplayRepository {

    // One extra row lets the stream explicitly signal a partial replay and reconnect safely.
    private static final int LIMIT = 2_001;
    private static final java.time.format.DateTimeFormatter CURSOR_TIME =
            new java.time.format.DateTimeFormatterBuilder().appendInstant(9).toFormatter();
    // Instant.toString omits trailing fractional zeroes. Pad fractions for chronological TEXT
    // comparison instead of letting a whole second sort after its fractional successors.
    private static final String ORDERED_TIME = "(substr(e.observed_at, 1, 19) || '.' || "
            + "substr((CASE WHEN substr(e.observed_at, 20, 1) = '.' "
            + "THEN substr(e.observed_at, 21, length(e.observed_at) - 21) ELSE '' END) "
            + "|| '000000000', 1, 9) || 'Z')";

    private final JdbcTemplate jdbcTemplate;

    public StreamReplayRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<StreamEventSnapshot> eventsSince(Instant since) {

        return jdbcTemplate.query(
                """
                SELECT e.id, e.session_id, e.source, e.event_type, e.role, e.text,
                       e.tool_name, e.observed_at, s.title, s.cwd, s.spawned_by
                  FROM agent_events e
                  JOIN agent_sessions s ON s.id = e.session_id
                 WHERE %s >= ?
                 ORDER BY %s ASC, e.id ASC
                 LIMIT ?
                """.formatted(ORDERED_TIME, ORDERED_TIME), this::mapSnapshot, CURSOR_TIME.format(since), LIMIT);
    }

    public List<StreamEventSnapshot> eventsAfterCursor(String lastEventId) {
        StreamCursor cursor = StreamCursor.parse(lastEventId);
        if (cursor == null) {

            return List.of();
        }

        return jdbcTemplate.query(
                """
                SELECT e.id, e.session_id, e.source, e.event_type, e.role, e.text,
                       e.tool_name, e.observed_at, s.title, s.cwd, s.spawned_by
                  FROM agent_events e
                  JOIN agent_sessions s ON s.id = e.session_id
                 WHERE %s > ?
                    OR (%s = ? AND e.id > ?)
                 ORDER BY %s ASC, e.id ASC
                 LIMIT ?
                """.formatted(ORDERED_TIME, ORDERED_TIME, ORDERED_TIME),
                this::mapSnapshot,
                CURSOR_TIME.format(cursor.observedAt()),
                CURSOR_TIME.format(cursor.observedAt()),
                cursor.id(),
                LIMIT);
    }

    private StreamEventSnapshot mapSnapshot(ResultSet rs, int rowNum) throws SQLException {

        return new StreamEventSnapshot(
                rs.getString("id"),
                rs.getString("session_id"),
                rs.getString("source"),
                rs.getString("event_type"),
                rs.getString("role"),
                rs.getString("text"),
                rs.getString("tool_name"),
                rs.getString("title"),
                Instant.parse(rs.getString("observed_at")),
                rs.getString("cwd"),
                rs.getString("spawned_by"));
    }

    private record StreamCursor(Instant observedAt, String id) {
        private static StreamCursor parse(String value) {
            if (value == null || value.isBlank()) {

                return null;
            }
            int separator = value.indexOf('|');
            if (separator <= 0 || separator == value.length() - 1) {

                return null;
            }
            try {

                return new StreamCursor(Instant.parse(value.substring(0, separator)), value.substring(separator + 1));
            } catch (DateTimeParseException ex) {

                return null;
            }
        }
    }
}
