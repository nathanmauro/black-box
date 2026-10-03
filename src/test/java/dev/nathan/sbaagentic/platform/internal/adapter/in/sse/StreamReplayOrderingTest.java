package dev.nathan.sbaagentic.platform.internal.adapter.in.sse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nathan.sbaagentic.platform.internal.adapter.out.sqlite.StreamReplayRepository;
import dev.nathan.sbaagentic.recording.EventIngestRequest;
import dev.nathan.sbaagentic.recording.internal.adapter.out.sqlite.RecordingSqlStore;
import dev.nathan.sbaagentic.recording.internal.adapter.out.sqlite.StreamPositionStore;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class StreamReplayOrderingTest {
    @TempDir
    Path directory;

    @Test
    void lateAndTiedCapturesReplayInCommitOrderWithoutChangingObservedTime() {
        var f = fixture();
        var first = f.capture("first", "2026-10-02T12:00:00Z");
        String cursor = f.replay.start(null, null).cursor();
        var late = f.capture("late", "2026-10-02T11:00:00Z");
        var tied = f.capture("tied", "2026-10-02T12:00:00Z");
        var page = f.replay.page(cursor, null, 2000);
        assertThat(page.entries()).extracting(e -> e.event().id()).containsExactly(late, tied);
        assertThat(page.entries().getFirst().event().observedAt()).isEqualTo(Instant.parse("2026-10-02T11:00:00Z"));
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM agent_events", Long.class))
                .isEqualTo(3);
        assertThat(f.replay.start(cursor, null).reset()).isFalse();
    }

    @Test
    void initialTimeSeekPreservesWholeSecondAndExtendedYearBoundaries() {
        var f = fixture();
        f.capture("previous-second", "2026-10-02T11:59:59.999999999Z");
        String boundary = f.capture("whole-second", "2026-10-02T12:00:00Z");
        String fraction = f.capture("fraction", "2026-10-02T12:00:00.000000001Z");
        String future = f.capture("extended-year", "+10000-01-01T00:00:00Z");
        Instant since = Instant.parse("2026-10-02T12:00:00Z");
        assertThat(f.replay
                        .page(f.replay.start(null, since).cursor(), since, 2000)
                        .entries())
                .extracting(e -> e.event().id())
                .containsExactly(boundary, fraction, future);
        Instant extended = Instant.parse("+10000-01-01T00:00:00Z");
        assertThat(f.replay
                        .page(f.replay.start(null, extended).cursor(), extended, 2000)
                        .entries())
                .extracting(e -> e.event() == null ? null : e.event().id())
                .containsExactly(null, null, null, future);
    }

    @Test
    void legacyInvalidForeignAndRestoredAwayAnchorsResetButRestartPreservesCursors() {
        var f = fixture();
        String empty = f.replay.start(null, null).cursor();
        f.capture("one", "2026-10-02T12:00:00Z");
        String one = f.replay.start(null, null).cursor();
        StreamPositionStore.initialize(f.jdbc);
        assertThat(f.replay.start(one, null).reset()).isFalse();
        assertThat(f.replay.page(empty, null, 2000).entries()).hasSize(1);
        for (String bad : List.of(
                "2026-10-02T12:00:00Z|old",
                "v2|broken",
                one.replace("|1|", "|99|"),
                one.replaceFirst("v2\\|[^|]+", "v2|other-database"),
                one + "x")) {
            assertThat(f.replay.start(bad, null).reset()).as(bad).isTrue();
        }
        // A restored snapshot may reuse a numeric position; its different event anchor detects it.
        f.jdbc.update("UPDATE event_stream_positions SET event_id = 'different-history' WHERE position = 1");
        assertThat(f.replay.start(one, null).reset()).isTrue();
    }

    @Test
    void migrationIsAtomicStableAndRetainsDeletedAnchors() {
        var f = fixture();
        f.capture("one", "2026-10-02T12:00:00Z");
        String cursor = f.replay.start(null, null).cursor();
        String event = f.jdbc.queryForObject("SELECT id FROM agent_events", String.class);
        f.jdbc.update("DELETE FROM agent_events WHERE id = ?", event);
        StreamPositionStore.initialize(f.jdbc);
        assertThat(f.replay.start(cursor, null).reset()).isFalse();
        assertThat(f.jdbc.queryForObject("SELECT last_position FROM event_stream_state", Long.class))
                .isEqualTo(1);
        f.jdbc.update(
                "INSERT INTO agent_events(id, session_id, source, client_session_id, event_type, observed_at) "
                        + "SELECT 'legacy', id, source, client_session_id, 'Observation', '2000-01-01T00:00:00Z' FROM agent_sessions LIMIT 1");
        StreamPositionStore.initialize(f.jdbc);
        assertThat(f.replay.page(cursor, null, 2000).entries())
                .extracting(e -> e.event().id())
                .containsExactly("legacy");
        assertThat(f.jdbc.queryForObject("SELECT observed_at FROM agent_events WHERE id='legacy'", String.class))
                .isEqualTo("2000-01-01T00:00:00Z");
        StreamPositionStore.initialize(f.jdbc);
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM event_stream_positions", Long.class))
                .isEqualTo(2);
    }

    @Test
    void receiptReplayAndRollbackAllocateNoAdditionalCommittedPosition() {
        var f = fixture();
        var event = request("receipt", "2026-10-02T12:00:00Z");
        var first = f.tx.execute(
                status -> f.store.persistIdempotentEvent("capture", "hash", event, event.observedAt(), "fixture", 1));
        var replay = f.tx.execute(
                status -> f.store.persistIdempotentEvent("capture", "hash", event, event.observedAt(), "fixture", 1));
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.persisted().event().id())
                .isEqualTo(first.persisted().event().id());
        assertThatThrownBy(() -> f.tx.executeWithoutResult(status -> {
                    f.store.persistEvent(request("rollback", "2026-10-02T11:00:00Z"), Instant.now(), "fixture", 1);
                    throw new IllegalStateException("rollback fixture");
                }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(f.jdbc.queryForObject("SELECT last_position FROM event_stream_state", Long.class))
                .isEqualTo(1);
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM event_stream_positions", Long.class))
                .isEqualTo(1);
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM agent_events", Long.class))
                .isEqualTo(1);
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM agent_sessions", Long.class))
                .isEqualTo(1);
    }

    @Test
    void reversedCallbacksAndHeartbeatDrainCanonicalOrderAndEmptyStreamHasCursor() throws Exception {
        var f = fixture();
        var broadcaster = new EventBroadcaster();
        var mvc = MockMvcBuilders.standaloneSetup(new FixtureController(broadcaster, f, null))
                .build();
        var stream = mvc.perform(get("/fixture-stream")).andReturn();
        assertThat(stream.getResponse().getContentAsString()).contains("event:stream.checkpoint", "|0|-");
        String a = f.capture("A", "2026-10-02T12:00:00Z");
        String b = f.capture("B", "2026-10-02T11:00:00Z");
        broadcaster.publishEventAppended(payload(b)); // B's callback arrives first.
        broadcaster.publishEventAppended(payload(a));
        String output = stream.getResponse().getContentAsString();
        assertThat(output.indexOf("\"id\":\"" + a)).isLessThan(output.indexOf("\"id\":\"" + b));
        assertThat(output).containsOnlyOnce("\"id\":\"" + a).containsOnlyOnce("\"id\":\"" + b);
        String lostPublication = f.capture("C", "2026-10-02T10:00:00Z");
        broadcaster.heartbeat();
        assertThat(stream.getResponse().getContentAsString()).contains(lostPublication);
        broadcaster.closeAll();
    }

    @Test
    void filteredAndDeletedPagesAdvanceCheckpointWithoutSkippingLookahead() throws Exception {
        var f = fixture();
        String start = f.replay.start(null, null).cursor();
        // SQL fixture imports are assigned positions by the same startup migration as old databases.
        f.jdbc.update(
                "INSERT INTO agent_sessions(id,source,client_session_id,title,started_at,last_seen_at) VALUES ('bulk','codex','bulk','fixture','2000-01-01T00:00:00Z','2000-01-01T00:00:00Z')");
        f.tx.executeWithoutResult(status -> {
            for (int i = 0; i < 2001; i++)
                f.jdbc.update(
                        "INSERT INTO agent_events(id,session_id,source,client_session_id,event_type,observed_at) VALUES (?,'bulk','codex','bulk','Observation','2000-01-01T00:00:00Z')",
                        "bulk-" + String.format("%04d", i));
        });
        StreamPositionStore.initialize(f.jdbc);
        f.jdbc.update("DELETE FROM agent_events WHERE id = 'bulk-0001'");
        var broadcaster = new EventBroadcaster();
        var mvc = MockMvcBuilders.standaloneSetup(new FixtureController(broadcaster, f, start))
                .build();
        var result = mvc.perform(get("/fixture-stream")).andReturn();
        String output = result.getResponse().getContentAsString();
        assertThat(output).contains("event:replay.more", "|2000|").doesNotContain("|2001|");
        assertThat(broadcaster.subscriberCount()).isZero();
        var firstPage = f.replay.page(start, Instant.parse("2026-01-01T00:00:00Z"), 2000);
        assertThat(firstPage.more()).isTrue();
        assertThat(firstPage.entries()).hasSize(2000).allMatch(e -> e.event() == null);
        var filteredLast =
                f.replay.page(firstPage.entries().getLast().cursor(), Instant.parse("2026-01-01T00:00:00Z"), 2000);
        assertThat(filteredLast.entries()).hasSize(1).allMatch(e -> e.event() == null);
        var lastPage = f.replay.page(firstPage.entries().getLast().cursor(), null, 2000);
        assertThat(lastPage.more()).isFalse();
        assertThat(lastPage.entries()).extracting(e -> e.event().id()).containsExactly("bulk-2000");
    }

    @Test
    void failedHistoricalBackfillRollsBackAllPositionsAndCounter() {
        var f = fixture();
        f.jdbc.update(
                "INSERT INTO agent_sessions(id,source,client_session_id,title,started_at,last_seen_at) VALUES ('s','codex','s','fixture','2000-01-01T00:00:00Z','2000-01-01T00:00:00Z')");
        for (String id : List.of("legacy-a", "legacy-bad"))
            f.jdbc.update(
                    "INSERT INTO agent_events(id,session_id,source,client_session_id,event_type,observed_at) VALUES (?,'s','codex','s','Observation','2000-01-01T00:00:00Z')",
                    id);
        f.jdbc.execute(
                "CREATE TRIGGER fail_backfill BEFORE UPDATE ON event_stream_state WHEN NEW.last_position > OLD.last_position BEGIN SELECT RAISE(ABORT, 'fixture backfill failure'); END");
        assertThatThrownBy(() -> StreamPositionStore.initialize(f.jdbc))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(f.jdbc.queryForObject("SELECT last_position FROM event_stream_state", Long.class))
                .isZero();
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM event_stream_positions", Long.class))
                .isZero();
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM agent_events", Long.class))
                .isEqualTo(2);
        f.jdbc.execute("DROP TRIGGER fail_backfill");
        StreamPositionStore.initialize(f.jdbc);
        assertThat(f.jdbc.queryForObject("SELECT last_position FROM event_stream_state", Long.class))
                .isEqualTo(2);
    }

    @Test
    void moderateHistoricalBackfillStaysSqlSideAndStableAcrossRestart() {
        var f = fixture();
        f.jdbc.update(
                "INSERT INTO agent_sessions(id,source,client_session_id,title,started_at,last_seen_at) VALUES ('s','codex','s','fixture','2000-01-01T00:00:00Z','2000-01-01T00:00:00Z')");
        f.jdbc.update(
                "WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i+1 FROM n WHERE i<20000) INSERT INTO agent_events(id,session_id,source,client_session_id,event_type,observed_at) SELECT 'legacy-' || i,'s','codex','s','Observation','2000-01-01T00:00:00Z' FROM n");
        long started = System.nanoTime();
        StreamPositionStore.initialize(f.jdbc);
        System.out.println(
                "Disposable 20000-event SQLite stream backfill: " + (System.nanoTime() - started) / 1_000_000 + " ms");
        String before = f.replay.start(null, null).cursor();
        StreamPositionStore.initialize(f.jdbc);
        assertThat(f.replay.start(null, null).cursor()).isEqualTo(before);
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM event_stream_positions", Long.class))
                .isEqualTo(20000);

        f.capture("just-before", "2026-10-03T12:00:00.123456788Z");
        String boundary = f.capture("at-boundary", "2026-10-03T12:00:00.123456789Z");
        Instant since = Instant.parse("2026-10-03T12:00:00.123456789Z");
        started = System.nanoTime();
        String recent = f.replay.start(null, since).cursor();
        var page = f.replay.page(recent, since, 2000);
        System.out.println(
                "Disposable 20000-event SQLite recent-time seek: " + (System.nanoTime() - started) / 1_000_000 + " ms");
        assertThat(page.more()).isFalse();
        assertThat(page.entries()).hasSize(2);
        assertThat(page.entries().getFirst().event()).isNull();
        assertThat(page.entries().getLast().event().id()).isEqualTo(boundary);
        String resume = page.entries().getLast().cursor();
        // Future writes remain append-ordered even when their timestamp falls before the high-water event.
        f.capture("excluded-late", "2000-01-01T00:00:00Z");
        String late = f.capture("included-late", "2026-10-03T12:00:00.123456789Z");
        assertThat(f.replay.page(resume, since, 2000).entries())
                .extracting(e -> e.event() == null ? null : e.event().id())
                .containsExactly(null, late);
        String future =
                f.replay.start(null, Instant.parse("2030-01-01T00:00:00Z")).cursor();
        assertThat(f.replay.page(future, null, 2000).entries()).isEmpty();
    }

    private Fixture fixture() {
        var ds = new DriverManagerDataSource("jdbc:sqlite:" + directory.resolve("fixture.db"));
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(ds);
        var jdbc = new JdbcTemplate(ds);
        var store = new RecordingSqlStore(jdbc, new ObjectMapper(), Clock.systemUTC(), null);
        store.ensureSchema();

        return new Fixture(
                jdbc,
                store,
                new TransactionTemplate(new DataSourceTransactionManager(ds)),
                new StreamReplayRepository(jdbc));
    }

    static EventIngestRequest request(String session, String time) {

        return new EventIngestRequest(
                "codex",
                session,
                null,
                "Observation",
                "assistant",
                session,
                "/fixture",
                null,
                null,
                null,
                Map.of(),
                Instant.parse(time));
    }

    private record Fixture(
            JdbcTemplate jdbc, RecordingSqlStore store, TransactionTemplate tx, StreamReplayRepository replay) {
        String capture(String session, String time) {
            var request = request(session, time);

            return tx.execute(status -> store.persistEvent(request, request.observedAt(), session, 1))
                    .event()
                    .id();
        }
    }

    private static StreamEvents.EventAppended payload(String id) {

        return new StreamEvents.EventAppended(
                "s",
                "codex",
                "Observation",
                null,
                "fixture",
                "2026-10-02T12:00:00Z",
                id,
                "/fixture",
                "assistant",
                id,
                null);
    }

    @RestController
    static class FixtureController {
        final EventBroadcaster broadcaster;
        final Fixture f;
        final String cursor;

        FixtureController(EventBroadcaster broadcaster, Fixture f, String cursor) {
            this.broadcaster = broadcaster;
            this.f = f;
            this.cursor = cursor;
        }

        @GetMapping("/fixture-stream")
        SseEmitter stream() {
            var start = f.replay.start(cursor, null);

            return broadcaster.register(() -> true, start.cursor(), start.reset(), last -> {
                var page = f.replay.page(last, null, 2000);

                return new EventBroadcaster.Page(
                        page.entries().stream()
                                .map(e -> new EventBroadcaster.Frame(
                                        e.cursor(),
                                        e.event() == null
                                                ? null
                                                : payload(e.event().id())))
                                .toList(),
                        page.more(),
                        page.resetCursor());
            });
        }
    }
}
