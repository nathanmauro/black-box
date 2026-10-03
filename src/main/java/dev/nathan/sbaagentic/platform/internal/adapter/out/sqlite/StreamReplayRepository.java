package dev.nathan.sbaagentic.platform.internal.adapter.out.sqlite;

import dev.nathan.sbaagentic.platform.internal.application.StreamEventSnapshot;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class StreamReplayRepository {
    public record Start(String cursor, boolean reset) {}

    public record Entry(String cursor, StreamEventSnapshot event) {}

    public record Page(List<Entry> entries, boolean more, String resetCursor) {}

    private record State(String generation, long position) {}

    private record Cursor(String generation, long position, String eventId) {
        String encode() {

            return "v2|" + generation + "|" + position + "|"
                    + (position == 0
                            ? "-"
                            : Base64.getUrlEncoder()
                                    .withoutPadding()
                                    .encodeToString(eventId.getBytes(StandardCharsets.UTF_8)));
        }
    }

    private final JdbcTemplate jdbc;

    public StreamReplayRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Legacy and invalid cursors explicitly request a snapshot reset, never a silent empty replay. */
    public Start start(String value, Instant since) {
        State state = state();
        if (value != null && !value.isBlank()) {
            Cursor cursor = parse(value);
            if (valid(cursor, state))

                return new Start(cursor.encode(), false);

            return new Start(checkpoint(state), true);
        }

        return new Start(since == null ? checkpoint(state) : initialCheckpoint(state, since), false);
    }

    private String initialCheckpoint(State state, Instant since) {
        String second = since.truncatedTo(ChronoUnit.SECONDS).toString();
        if (second.startsWith("+") || second.startsWith("-"))

            return new Cursor(state.generation(), 0, "").encode();

        // Stored Instants are UTC ISO text. Removing Z makes this a conservative lower bound
        // for every fractional precision in that second; page() retains the exact time filter.
        // Include extended-year text conservatively because its lexical order differs. Cap at
        // the captured high-water so a concurrent late append can never be skipped by the seek.
        Long first = jdbc.queryForObject("""
                SELECT MIN(p.position)
                  FROM agent_events e
                  JOIN event_stream_positions p ON p.event_id = e.id
                 WHERE (e.observed_at >= ? OR e.observed_at LIKE '+%' OR e.observed_at LIKE '-%')
                   AND p.position <= ?
                """, Long.class, second.substring(0, second.length() - 1), state.position());

        return checkpoint(new State(state.generation(), first == null ? state.position() : first - 1));
    }

    public Page page(String value, Instant since, int limit) {
        State state = state();
        Cursor cursor = parse(value);
        if (!valid(cursor, state))

            return new Page(List.of(), false, checkpoint(state));

        // Limit raw positions, not matching payloads: deleted/filtered entries must also advance
        // the checkpoint so a stream can make bounded progress through any historical corpus.
        List<Entry> entries = jdbc.query(
                """
                SELECT p.position, p.event_id, e.id, e.session_id, e.source, e.event_type,
                       e.role, e.text, e.tool_name, e.observed_at, s.title, s.cwd, s.spawned_by
                  FROM event_stream_positions p
                  LEFT JOIN agent_events e ON e.id = p.event_id
                  LEFT JOIN agent_sessions s ON s.id = e.session_id
                 WHERE p.position > ?
                 ORDER BY p.position
                 LIMIT ?
                """,
                (rs, row) -> {
                    StreamEventSnapshot event = rs.getString("id") == null ? null : snapshot(rs);
                    if (event != null && since != null && event.observedAt().isBefore(since)) event = null;

                    return new Entry(
                            new Cursor(state.generation(), rs.getLong("position"), rs.getString("event_id")).encode(),
                            event);
                },
                cursor.position(),
                limit + 1);

        return new Page(entries.stream().limit(limit).toList(), entries.size() > limit, null);
    }

    private State state() {

        return jdbc.queryForObject(
                "SELECT generation, last_position FROM event_stream_state WHERE id = 1",
                (rs, row) -> new State(rs.getString("generation"), rs.getLong("last_position")));
    }

    private String checkpoint(State state) {
        String id = state.position() == 0
                ? ""
                : jdbc.queryForObject(
                        "SELECT event_id FROM event_stream_positions WHERE position = ?",
                        String.class,
                        state.position());

        return new Cursor(state.generation(), state.position(), id).encode();
    }

    private boolean valid(Cursor cursor, State state) {
        if (cursor == null || !state.generation().equals(cursor.generation()) || cursor.position() > state.position())

            return false;
        if (cursor.position() == 0)

            return cursor.eventId().isEmpty();

        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM event_stream_positions WHERE position = ? AND event_id = ?)",
                Boolean.class,
                cursor.position(),
                cursor.eventId()));
    }

    private static Cursor parse(String value) {
        if (value == null || value.length() > 2048)

            return null;

        try {
            String[] parts = value.split("\\|", -1);
            if (parts.length != 4 || !parts[0].equals("v2"))

                return null;

            long position = Long.parseLong(parts[2]);
            if (position < 0)

                return null;

            String id = position == 0 && parts[3].equals("-")
                    ? ""
                    : new String(Base64.getUrlDecoder().decode(parts[3]), StandardCharsets.UTF_8);
            Cursor result = new Cursor(parts[1], position, id);

            return result.encode().equals(value) ? result : null;
        } catch (IllegalArgumentException ex) {

            return null;
        }
    }

    private static StreamEventSnapshot snapshot(ResultSet rs) throws SQLException {

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
}
