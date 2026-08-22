package dev.nathan.sbaagentic.recording;

import dev.nathan.sbaagentic.recording.internal.adapter.out.sqlite.EventFtsIndex;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the FTS5 index contract: trigger population on insert/delete/update, consistency across
 * rowid reuse after DELETE (MATCH must never return a different event), backfill/rebuild
 * idempotency with resumable progress, and the probe failing soft when FTS5 (or the table name)
 * is unusable.
 */
@SpringBootTest(properties = {
        // A temp file DB takes the production WAL + busy_timeout path; cache=shared
        // memory throws SQLITE_LOCKED on writer collisions, ignoring busy_timeout.
        "spring.datasource.url=jdbc:sqlite:${java.io.tmpdir}/bb-event-fts-test-${random.uuid}.db",
        "sba.local-ai.enabled=false",
        "sba.summary.backend=local",
        "sba.elasticsearch.enabled=false",
        "sba.memory.embedding.enabled=false"
})
class EventFtsIndexTest {

    @Autowired
    EventRecorder ingestService;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    EventFtsIndex ftsIndex;

    @Test
    void freshDatabaseIsAvailableAndReadyImmediately() {
        assertThat(ftsIndex.available()).isTrue();
        assertThat(ftsIndex.ready()).isTrue();
    }

    @Test
    void insertTriggerIndexesNewEvents() {
        String term = uniqueTerm();
        String eventId = seed("Insert trigger " + term, null);

        assertThat(matchedEventIds(term)).containsExactly(eventId);
    }

    @Test
    void deleteTriggerAndRowidReuseNeverReturnWrongRows() {
        String termA = uniqueTerm();
        String termB = uniqueTerm();
        String eventA = seed("Reuse victim " + termA, null);
        long rowidA = rowidOf(eventA);

        jdbcTemplate.update("DELETE FROM agent_events WHERE id = ?", eventA);
        String eventB = seed("Reuse successor " + termB, null);
        long rowidB = rowidOf(eventB);

        // eventA held the max rowid when deleted, so SQLite hands the same rowid to eventB —
        // exactly the reuse scenario where a stale FTS entry would resolve to the WRONG event.
        assertThat(rowidB).isEqualTo(rowidA);
        assertThat(matchedEventIds(termA)).isEmpty();
        assertThat(matchedEventIds(termB)).containsExactly(eventB);
    }

    @Test
    void updateTriggerReindexesTheRow() {
        String before = uniqueTerm();
        String after = uniqueTerm();
        String eventId = seed("Update original " + before, null);

        jdbcTemplate.update("UPDATE agent_events SET text = ? WHERE id = ?",
                "Update replacement " + after, eventId);

        assertThat(matchedEventIds(before)).isEmpty();
        assertThat(matchedEventIds(after)).containsExactly(eventId);
    }

    @Test
    void extraColumnMakesToolOutputSearchable() {
        String term = uniqueTerm();
        String eventId = seed("Tool row with plain text", "{\"is_error\":true,\"detail\":\"" + term + "\"}");

        assertThat(matchedEventIds(term)).containsExactly(eventId);
    }

    @Test
    void rebuildIsIdempotentAndRecordsProgress() {
        String term = uniqueTerm();
        String first = seed("Rebuild one " + term, null);
        String second = seed("Rebuild two " + term, null);
        String third = seed("Rebuild three " + term, null);

        ftsIndex.rebuild();
        assertThat(matchCount(term)).isEqualTo(3);
        assertThat(matchedEventIds(term)).containsExactlyInAnyOrder(first, second, third);
        Map<String, Object> state = jdbcTemplate.queryForMap(
                "SELECT last_rowid, target_rowid, complete FROM search_index_state WHERE id = 'event_fts_backfill'");
        assertThat(((Number) state.get("complete")).intValue()).isEqualTo(1);
        assertThat(((Number) state.get("last_rowid")).longValue())
                .isEqualTo(((Number) state.get("target_rowid")).longValue());
        assertThat(ftsIndex.ready()).isTrue();

        ftsIndex.rebuild();
        assertThat(matchCount(term)).isEqualTo(3);
    }

    @Test
    void backfillResumesFromRecordedProgressWithoutDuplicates() {
        String term = uniqueTerm();
        String first = seed("Resume one " + term, null);
        String second = seed("Resume two " + term, null);
        String third = seed("Resume three " + term, null);
        long firstRowid = rowidOf(first);
        long targetRowid = rowidOf(third);

        // Simulate a crash mid-backfill: wipe the index, replay only the first row's entry, and
        // leave the progress row pointing at it.
        jdbcTemplate.execute("INSERT INTO event_fts(event_fts) VALUES('delete-all')");
        jdbcTemplate.update("""
                INSERT INTO event_fts(rowid, text, tool_name, extra)
                SELECT rowid, coalesce(text, ''), coalesce(tool_name, ''), ''
                  FROM agent_events WHERE rowid = ?
                """, firstRowid);
        jdbcTemplate.update("""
                UPDATE search_index_state
                   SET last_rowid = ?, target_rowid = ?, complete = 0, updated_at = 'test'
                 WHERE id = 'event_fts_backfill'
                """, firstRowid, targetRowid);

        ftsIndex.runBackfill();

        assertThat(matchCount(term)).isEqualTo(3);
        assertThat(matchedEventIds(term)).containsExactlyInAnyOrder(first, second, third);
        assertThat(ftsIndex.ready()).isTrue();
    }

    @Test
    void probeFailureFailsSoft(@TempDir Path tempDir) throws Exception {
        Path db = Files.createTempFile(tempDir, "fts-probe", ".db");
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + db);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        // A plain table squatting on the name: CREATE VIRTUAL TABLE IF NOT EXISTS silently
        // no-ops, so only the MATCH smoke probe can catch it.
        jdbc.execute("CREATE TABLE event_fts (x INTEGER)");

        EventFtsIndex broken = new EventFtsIndex(jdbc, Clock.systemDefaultZone());
        broken.ensureFtsSchema();

        assertThat(broken.available()).isFalse();
        assertThat(broken.ready()).isFalse();
        broken.runBackfill();
        broken.rebuild();
    }

    private String seed(String text, String toolOutputJson) {
        String clientSessionId = "fts-" + UUID.randomUUID().toString().replace("-", "");
        return ingestService.ingest(new EventIngestRequest(
                "codex", clientSessionId, "turn-1", "Decision", "assistant",
                text, "/tmp/fts", toolOutputJson == null ? null : "Bash", null, toolOutputJson,
                Map.of("title", "FTS " + clientSessionId),
                Instant.parse("2026-07-01T12:00:00Z"))).eventId();
    }

    private long rowidOf(String eventId) {
        Long rowid = jdbcTemplate.queryForObject(
                "SELECT rowid FROM agent_events WHERE id = ?", Long.class, eventId);
        assertThat(rowid).isNotNull();
        return rowid;
    }

    private int matchCount(String term) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM event_fts WHERE event_fts MATCH ?",
                Integer.class, EventFtsIndex.matchExpression(List.of(term)));
        return count == null ? 0 : count;
    }

    private List<String> matchedEventIds(String term) {
        return jdbcTemplate.queryForList("""
                SELECT e.id FROM agent_events e
                 WHERE e.rowid IN (SELECT rowid FROM event_fts WHERE event_fts MATCH ?)
                """, String.class, EventFtsIndex.matchExpression(List.of(term)));
    }

    private static String uniqueTerm() {
        return "ftsterm" + UUID.randomUUID().toString().replace("-", "");
    }
}
