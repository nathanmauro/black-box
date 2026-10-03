package dev.nathan.sbaagentic.recording.internal.adapter.out.sqlite;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Commit-ordered append positions; independent of caller timestamps and optional publications. */
public final class StreamPositionStore {
    private StreamPositionStore() {}

    public static void initialize(JdbcTemplate jdbc) {
        new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())).executeWithoutResult(status -> {
            jdbc.update(
                    "INSERT INTO event_stream_state(id, generation, last_position) VALUES (1, ?, 0) ON CONFLICT(id) DO NOTHING",
                    UUID.randomUUID().toString());
            // First write acquires SQLite's writer lock and PostgreSQL's singleton row lock.
            jdbc.update("UPDATE event_stream_state SET last_position = last_position WHERE id = 1");
            Long last = jdbc.queryForObject("SELECT last_position FROM event_stream_state WHERE id = 1", Long.class);
            Long maximum =
                    jdbc.queryForObject("SELECT COALESCE(MAX(position), 0) FROM event_stream_positions", Long.class);
            if (last == null || maximum == null || maximum > last) {
                throw new IllegalStateException("Stream counter is inconsistent with its durable positions");
            }
            // Stable historical order only; original commit chronology cannot be recovered.
            // SQL-side numbering avoids loading a legacy corpus into the JVM. Existing anchors,
            // including tombstones for removed payloads, are never renumbered or deleted.
            int added = jdbc.update("""
                    INSERT INTO event_stream_positions(position, event_id)
                    SELECT ? + ROW_NUMBER() OVER (ORDER BY e.observed_at, e.id), e.id
                      FROM agent_events e
                     WHERE NOT EXISTS (SELECT 1 FROM event_stream_positions p WHERE p.event_id = e.id)
                    """, last);
            jdbc.update("UPDATE event_stream_state SET last_position = last_position + ? WHERE id = 1", added);
        });
    }

    static long allocate(JdbcTemplate jdbc) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Stream positions require the canonical append transaction");
        }
        if (jdbc.update(
                        "UPDATE event_stream_state SET last_position = last_position + 1 WHERE id = 1 AND last_position < 9223372036854775807")
                != 1) {
            throw new IllegalStateException("Stream position counter is missing or exhausted");
        }

        // The update's row lock is retained until the OUTER capture transaction commits, including
        // receipt/replacement binding. A PostgreSQL sequence would not provide commit ordering.
        return jdbc.queryForObject("SELECT last_position FROM event_stream_state WHERE id = 1", Long.class);
    }
}
