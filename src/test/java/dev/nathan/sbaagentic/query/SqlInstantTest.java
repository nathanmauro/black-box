package dev.nathan.sbaagentic.query;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nathan.sbaagentic.recording.internal.adapter.out.sqlite.RecordingSqlStore;
import dev.nathan.sbaagentic.recording.internal.adapter.out.sqlite.StreamPositionStore;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

class SqlInstantTest {
    @TempDir
    Path directory;

    @Test
    void sqliteKeysAgreeWithJavaAcrossTheEntireInstantRange() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            SqlInstantAssertions.assertDialect(
                    new JdbcTemplate(new SingleConnectionDataSource(connection, true)), false);
        }
    }

    @Test
    void moderateMigrationKeepsCanonicalBytesAndSeeksDeepCursorThroughMatchingIndexes() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("chronology.db"))) {
            var source = new SingleConnectionDataSource(connection, true);
            var jdbc = new JdbcTemplate(source);
            new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(source);
            jdbc.update(
                    "INSERT INTO agent_sessions(id,source,client_session_id,title,started_at,last_seen_at) "
                            + "VALUES('fixture','codex','fixture','fixture',?,?)",
                    Instant.EPOCH.toString(),
                    Instant.EPOCH.toString());
            Instant base = Instant.parse("2026-10-03T00:00:00Z");
            List<Object[]> rows = new ArrayList<>();
            for (int i = 0; i < 50_000; i++) {
                rows.add(new Object[] {
                    String.format("event-%05d", i),
                    base.plusSeconds(i / 4).plusNanos((i % 4) * 100_000_001L).toString(),
                    i % 10 == 0 ? "human" : null,
                    i % 100 == 0 ? "Decision" : "PostToolUse"
                });
            }
            connection.setAutoCommit(false);
            jdbc.batchUpdate(
                    "INSERT INTO agent_events(id,session_id,source,client_session_id,observed_at,human_text,event_type) "
                            + "VALUES(?,'fixture','codex','fixture',?,?,?)",
                    rows);
            connection.commit();
            connection.setAutoCommit(true);
            // Existing durable stream migration is outside the measured chronological index build.
            StreamPositionStore.initialize(jdbc);
            jdbc.execute(
                    "CREATE INDEX idx_agent_events_human ON agent_events(observed_at DESC,id DESC) WHERE human_text IS NOT NULL");
            List<String> original = jdbc.queryForList("SELECT observed_at FROM agent_events ORDER BY id", String.class);
            long bytesBefore = jdbc.queryForObject("PRAGMA page_count", Long.class)
                    * jdbc.queryForObject("PRAGMA page_size", Long.class);
            long start = System.nanoTime();
            var store = new RecordingSqlStore(jdbc, new ObjectMapper(), Clock.systemUTC(), null);
            store.ensureSchema();
            long buildMillis = (System.nanoTime() - start) / 1_000_000;
            long bytesAdded = jdbc.queryForObject("PRAGMA page_count", Long.class)
                            * jdbc.queryForObject("PRAGMA page_size", Long.class)
                    - bytesBefore;
            long restartStart = System.nanoTime();
            store.ensureSchema();
            long restartMillis = (System.nanoTime() - restartStart) / 1_000_000;
            assertThat(jdbc.queryForList("SELECT observed_at FROM agent_events ORDER BY id", String.class))
                    .isEqualTo(original);
            assertThat(jdbc.queryForObject(
                            "SELECT count(*) FROM sqlite_master WHERE type='index' AND name LIKE 'idx_agent_events_%chronology_v1'",
                            Integer.class))
                    .isEqualTo(3);
            SqlInstant key = SqlInstant.column("e.observed_at", false);
            String cursor = SqlInstant.key(base.plusSeconds(250));
            String firstSql = "SELECT id FROM agent_events e ORDER BY " + key.descending("e.id") + " LIMIT 100";
            String firstPlan =
                    jdbc.queryForList("EXPLAIN QUERY PLAN " + firstSql).toString();
            assertThat(firstPlan).contains("idx_agent_events_chronology_v1").doesNotContain("TEMP B-TREE");
            long firstStart = System.nanoTime();
            assertThat(jdbc.queryForList(firstSql, String.class)).hasSize(100).startsWith("event-49999");
            System.out.printf(
                    "Chronology fixture: restart=%dms, head=%dus, plan=%s%n",
                    restartMillis, (System.nanoTime() - firstStart) / 1_000, firstPlan);
            String recallSql = "SELECT e.id FROM agent_events e JOIN agent_sessions s ON e.session_id=s.id "
                    + "WHERE e.event_type IN ('Decision') AND " + key.expression() + " >= ? "
                    + "AND NOT EXISTS (SELECT 1 FROM decision_replacements r WHERE r.superseded_event_id=e.id) "
                    + "ORDER BY " + key.descending("e.id") + " LIMIT 100";
            String recallPlan =
                    jdbc.queryForList("EXPLAIN QUERY PLAN " + recallSql, cursor).toString();
            var memory = new dev.nathan.sbaagentic.memory.internal.adapter.out.sqlite.MemorySqlQueryAdapter(
                    jdbc, new ObjectMapper(), Clock.systemUTC());
            long recallStart = System.nanoTime();
            assertThat(memory.recall(List.of("Decision"), null, base.plusSeconds(250), 100))
                    .hasSize(100)
                    .extracting(event -> event.id())
                    .startsWith("event-49900");
            System.out.printf(
                    "Chronology fixture: 1%% intent among hook rows, recall=%dus, plan=%s%n",
                    (System.nanoTime() - recallStart) / 1_000, recallPlan);
            for (String filter : List.of("", " AND e.session_id = 'fixture'", " AND e.human_text IS NOT NULL")) {
                String sql = "SELECT id FROM agent_events e WHERE " + key.expression() + " <= ? AND "
                        + key.cursorTuple("e.id") + " < (?,?)" + filter + " ORDER BY " + key.descending("e.id")
                        + " LIMIT 100";
                String plan = jdbc.queryForList("EXPLAIN QUERY PLAN " + sql, cursor, cursor, "event-01000")
                        .toString();
                assertThat(plan)
                        .contains("SEARCH e USING INDEX idx_agent_events_")
                        .contains("chronology_v1")
                        .doesNotContain("TEMP B-TREE");
                long queryStart = System.nanoTime();
                List<String> result = jdbc.queryForList(sql, String.class, cursor, cursor, "event-01000");
                long queryMicros = (System.nanoTime() - queryStart) / 1_000;
                assertThat(result).hasSize(100).doesNotHaveDuplicates().allMatch(id -> id.compareTo("event-01000") < 0);
                System.out.printf(
                        "Chronology fixture 50000 rows: build=%dms, index bytes=%d, filter=%s, deep cursor=%dus, plan=%s%n",
                        buildMillis, bytesAdded, filter, queryMicros, plan);
            }
        }
    }
}
