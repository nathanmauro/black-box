package dev.nathan.sbaagentic.recording.internal.adapter.out.sqlite;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import jakarta.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Contentless FTS5 index over {@code agent_events}, keyed to its implicit rowid. The DDL runs
 * programmatically (never in schema.sql: the script runner splits on {@code ;} and would break
 * trigger bodies, and a hard CREATE VIRTUAL TABLE would fail startup on an FTS5-less driver
 * instead of failing soft) behind a try-create-and-probe; on any failure FTS is marked unavailable
 * and free-text search stays on the per-term LIKE path with identical semantics.
 *
 * <p>Triggers cover INSERT, DELETE, and UPDATE (delete + reinsert via {@code contentless_delete=1})
 * and fire inside the same transaction as the canonical write — an internal index, not fan-out.
 * This keeps the index consistent by construction across test suites that {@code DELETE FROM
 * agent_events}, and closes the rowid-reuse hazard (a stale entry surviving a DELETE would make
 * MATCH return a <em>different</em> event, not just miss). The triggers live in SQLite, so they
 * keep maintaining the index even while this bean considers FTS unavailable; re-probing restores
 * a consistent index without a rebuild.
 *
 * <p>{@code extra} clips tool JSON <strong>output-first</strong> (failures live in output; 3,246
 * live events have inputs alone ≥8k, so an input-first clip would exclude their output entirely):
 * 6000 chars of tool_output_json, 4000 of metadata_json, 2000 of tool_input_json.
 *
 * <p>Backfill is chunked (10k rows/batch), resumable via one {@code search_index_state} row, runs
 * in the background post-startup, and doubles as the rebuild job after
 * {@code INSERT INTO event_fts(event_fts) VALUES('delete-all')}. Named invariant: never VACUUM the
 * live database without a rebuild — {@code agent_events} has a TEXT primary key, so VACUUM may
 * renumber its implicit rowids and silently remap every FTS hit.
 */
@Repository
public class EventFtsIndex {

    private static final Logger log = LoggerFactory.getLogger(EventFtsIndex.class);

    static final int BATCH_SIZE = 10_000;
    private static final String STATE_ID = "event_fts_backfill";

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;
    private volatile boolean available;
    private volatile boolean backfillComplete;

    public EventFtsIndex(JdbcTemplate jdbcTemplate, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    /**
     * Try-create probe: builds the virtual table, smoke-queries it (catches a plain table squatting
     * on the name or a driver without FTS5), installs the triggers and the progress table, and
     * seeds the progress row. Any failure logs once and leaves everything on the LIKE path.
     */
    @PostConstruct
    public void ensureFtsSchema() {
        try {
            jdbcTemplate.execute("""
                    CREATE VIRTUAL TABLE IF NOT EXISTS event_fts
                    USING fts5(text, tool_name, extra, content='', contentless_delete=1)
                    """);
            jdbcTemplate.execute("SELECT count(*) FROM event_fts WHERE event_fts MATCH '\"__fts_probe__\"'");
            jdbcTemplate.execute("""
                    CREATE TRIGGER IF NOT EXISTS agent_events_fts_ai AFTER INSERT ON agent_events BEGIN
                      INSERT INTO event_fts(rowid, text, tool_name, extra)
                      VALUES (new.rowid, coalesce(new.text, ''), coalesce(new.tool_name, ''), %s);
                    END
                    """.formatted(extraExpression("new.")));
            jdbcTemplate.execute("""
                    CREATE TRIGGER IF NOT EXISTS agent_events_fts_ad AFTER DELETE ON agent_events BEGIN
                      DELETE FROM event_fts WHERE rowid = old.rowid;
                    END
                    """);
            jdbcTemplate.execute("""
                    CREATE TRIGGER IF NOT EXISTS agent_events_fts_au AFTER UPDATE ON agent_events BEGIN
                      DELETE FROM event_fts WHERE rowid = old.rowid;
                      INSERT INTO event_fts(rowid, text, tool_name, extra)
                      VALUES (new.rowid, coalesce(new.text, ''), coalesce(new.tool_name, ''), %s);
                    END
                    """.formatted(extraExpression("new.")));
            jdbcTemplate.execute("""
                    CREATE TABLE IF NOT EXISTS search_index_state (
                      id TEXT PRIMARY KEY,
                      last_rowid INTEGER NOT NULL,
                      target_rowid INTEGER NOT NULL,
                      complete INTEGER NOT NULL,
                      updated_at TEXT NOT NULL
                    )
                    """);
            // Seed the progress row once: everything ingested after this moment is trigger-covered,
            // so the backfill's job is exactly rowids up to the target captured here.
            jdbcTemplate.update("""
                    INSERT INTO search_index_state (id, last_rowid, target_rowid, complete, updated_at)
                    SELECT ?, 0,
                           coalesce((SELECT max(rowid) FROM agent_events), 0),
                           CASE WHEN coalesce((SELECT max(rowid) FROM agent_events), 0) = 0 THEN 1 ELSE 0 END,
                           ?
                     WHERE NOT EXISTS (SELECT 1 FROM search_index_state WHERE id = ?)
                    """, STATE_ID, clock.instant().toString(), STATE_ID);
            available = true;
            backfillComplete = stateComplete();
        }
        catch (RuntimeException ex) {
            available = false;
            backfillComplete = false;
            log.warn("FTS5 unavailable; free-text search stays on the LIKE fallback: {}", ex.getMessage());
        }
    }

    /** Table and triggers are installed and healthy. */
    public boolean available() {
        return available;
    }

    /** MATCH may serve queries: installed, healthy, and the backfill has covered every old row. */
    public boolean ready() {
        return available && backfillComplete;
    }

    /** Fail-soft switch, flipped by the feed on a MATCH error; a later re-probe can restore. */
    public void markUnavailable() {
        if (available) {
            log.warn("FTS5 query failed; falling back to LIKE until the next re-probe");
        }
        available = false;
        backfillComplete = false;
    }

    /** Kicks the chunked backfill off the request path once the application is up. */
    @EventListener(ApplicationReadyEvent.class)
    public void startBackgroundBackfill() {
        if (!available || backfillComplete) {
            return;
        }
        Thread worker = new Thread(this::runBackfillSafely, "event-fts-backfill");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * Chunked, idempotent, resumable backfill: each batch bulk-inserts the next
     * {@value #BATCH_SIZE} rowids up to the recorded target and advances the progress row, so a
     * restart resumes where it left off. Rows ingested after the target are trigger-covered and
     * never touched here.
     */
    public void runBackfill() {
        if (!available) {
            return;
        }
        while (true) {
            Map<String, Object> state = state();
            if (state == null || ((Number) state.get("complete")).intValue() == 1) {
                backfillComplete = state != null;
                return;
            }
            long last = ((Number) state.get("last_rowid")).longValue();
            long target = ((Number) state.get("target_rowid")).longValue();
            Long batchEnd = jdbcTemplate.queryForObject("""
                    SELECT max(rowid) FROM (
                      SELECT rowid FROM agent_events
                       WHERE rowid > ? AND rowid <= ?
                       ORDER BY rowid LIMIT ?
                    )
                    """, Long.class, last, target, BATCH_SIZE);
            if (batchEnd == null) {
                markComplete(target);
                return;
            }
            jdbcTemplate.update("""
                    INSERT INTO event_fts(rowid, text, tool_name, extra)
                    SELECT rowid, coalesce(text, ''), coalesce(tool_name, ''), %s
                      FROM agent_events
                     WHERE rowid > ? AND rowid <= ?
                    """.formatted(extraExpression("")), last, batchEnd);
            if (batchEnd >= target) {
                markComplete(target);
                return;
            }
            jdbcTemplate.update(
                    "UPDATE search_index_state SET last_rowid = ?, updated_at = ? WHERE id = ?",
                    batchEnd, clock.instant().toString(), STATE_ID);
        }
    }

    /**
     * Rebuild = delete-all + the same backfill job. The target is captured after the wipe, so a
     * concurrent ingest can at worst be indexed twice (its trigger entry plus the backfill copy) —
     * a duplicate entry is invisible to the {@code rowid IN} query shape — never lost.
     */
    public void rebuild() {
        if (!available) {
            return;
        }
        backfillComplete = false;
        jdbcTemplate.execute("INSERT INTO event_fts(event_fts) VALUES('delete-all')");
        jdbcTemplate.update("""
                UPDATE search_index_state
                   SET last_rowid = 0,
                       target_rowid = coalesce((SELECT max(rowid) FROM agent_events), 0),
                       complete = CASE WHEN coalesce((SELECT max(rowid) FROM agent_events), 0) = 0 THEN 1 ELSE 0 END,
                       updated_at = ?
                 WHERE id = ?
                """, clock.instant().toString(), STATE_ID);
        runBackfill();
    }

    /**
     * Compiles free terms into one FTS5 MATCH expression: each term double-quote-escaped, quoted,
     * and prefix-starred; space separation is FTS5's implicit AND. Quoting neutralizes every piece
     * of MATCH syntax (NEAR, -, ^, boolean keywords), so user text can never reach the parser, and
     * a multi-word term (a quoted phrase in the grammar) becomes a phrase query.
     */
    public static String matchExpression(List<String> terms) {
        return terms.stream()
                .map(term -> "\"" + term.replace("\"", "\"\"") + "\"*")
                .collect(Collectors.joining(" "));
    }

    static String extraExpression(String prefix) {
        return ("substr(coalesce(%stool_output_json, ''), 1, 6000) || ' ' || "
                + "substr(coalesce(%smetadata_json, ''), 1, 4000) || ' ' || "
                + "substr(coalesce(%stool_input_json, ''), 1, 2000)")
                .formatted(prefix, prefix, prefix);
    }

    private void runBackfillSafely() {
        try {
            runBackfill();
            log.info("FTS5 backfill complete");
        }
        catch (RuntimeException ex) {
            log.warn("FTS5 backfill failed; free-text search stays on the LIKE fallback: {}", ex.getMessage());
        }
    }

    private void markComplete(long target) {
        jdbcTemplate.update(
                "UPDATE search_index_state SET last_rowid = ?, complete = 1, updated_at = ? WHERE id = ?",
                target, clock.instant().toString(), STATE_ID);
        backfillComplete = true;
    }

    private boolean stateComplete() {
        Map<String, Object> state = state();
        return state != null && ((Number) state.get("complete")).intValue() == 1;
    }

    private Map<String, Object> state() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT last_rowid, target_rowid, complete FROM search_index_state WHERE id = ?", STATE_ID);
        return rows.isEmpty() ? null : rows.get(0);
    }
}
