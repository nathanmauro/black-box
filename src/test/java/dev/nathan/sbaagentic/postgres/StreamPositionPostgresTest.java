package dev.nathan.sbaagentic.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nathan.sbaagentic.platform.internal.adapter.out.sqlite.StreamReplayRepository;
import dev.nathan.sbaagentic.recording.EventIngestRequest;
import dev.nathan.sbaagentic.recording.internal.adapter.out.sqlite.RecordingSqlStore;
import dev.nathan.sbaagentic.recording.internal.adapter.out.sqlite.StreamPositionStore;
import java.net.URI;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

@EnabledIfEnvironmentVariable(named = "SBA_POSTGRES_TEST_URL", matches = "jdbc:postgresql:.+")
class StreamPositionPostgresTest {
    @Test
    void concurrentDifferentSessionsCommitInAllocatedOrder() throws Exception {
        concurrent(false);
    }

    @Test
    void rollbackReleasesCounterWithoutOrphanPositionReceiptOrSession() throws Exception {
        concurrent(true);
    }

    private void concurrent(boolean rollback) throws Exception {
        String url = System.getenv("SBA_POSTGRES_TEST_URL");
        URI address = URI.create(url.substring(5));
        if (address.getRawQuery() != null || address.getRawFragment() != null || address.getUserInfo() != null) {
            throw new IllegalArgumentException("Fixture URL must not override its isolated schema");
        }
        Properties credentials = new Properties();
        credentials.setProperty("user", System.getenv().getOrDefault("SBA_POSTGRES_TEST_USERNAME", "postgres"));
        credentials.setProperty("password", System.getenv().getOrDefault("SBA_POSTGRES_TEST_PASSWORD", ""));
        String schema = "bb_stream_" + UUID.randomUUID().toString().replace("-", "");
        try (var admin = DriverManager.getConnection(url, credentials)) {
            try (var statement = admin.createStatement()) {
                statement.execute("CREATE SCHEMA " + schema);
            }
            try {
                var ds = new DriverManagerDataSource(url);
                Properties isolated = new Properties();
                isolated.putAll(credentials);
                isolated.setProperty("currentSchema", schema);
                ds.setConnectionProperties(isolated);
                new ResourceDatabasePopulator(new ClassPathResource("schema-postgres.sql")).execute(ds);
                var jdbc = new JdbcTemplate(ds);
                var store = new RecordingSqlStore(jdbc, new ObjectMapper(), Clock.systemUTC(), null, "postgres");
                store.ensureSchema();
                var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
                var replay = new StreamReplayRepository(jdbc);
                String empty = replay.start(null, null).cursor();
                var allocated = new CountDownLatch(1);
                var release = new CountDownLatch(1);
                var secondStarted = new CountDownLatch(1);
                try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                    var first = executor.submit(() -> tx.execute(status -> {
                        var request = event("A", "2026-10-03T12:00:00Z");
                        var persisted = store.persistIdempotentEvent(
                                "capture-A", "hash-A", request, request.observedAt(), "A", 1);
                        allocated.countDown();
                        try {
                            if (!release.await(10, TimeUnit.SECONDS))
                                throw new IllegalStateException("fixture latch timed out");
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(ex);
                        }
                        if (rollback) status.setRollbackOnly();

                        return persisted.persisted().event().id();
                    }));
                    assertThat(allocated.await(10, TimeUnit.SECONDS)).isTrue();
                    var second = executor.submit(() -> tx.execute(status -> {
                        secondStarted.countDown();
                        var request = event("B", "2026-10-03T11:00:00Z");

                        return store.persistEvent(request, request.observedAt(), "B", 1)
                                .event()
                                .id();
                    }));
                    try {
                        assertThat(secondStarted.await(10, TimeUnit.SECONDS)).isTrue();
                        assertThatThrownBy(() -> second.get(250, TimeUnit.MILLISECONDS))
                                .isInstanceOf(TimeoutException.class);
                        assertThat(replay.page(empty, null, 2000).entries()).isEmpty();
                        assertThat(replay.start(null, Instant.parse("2026-10-03T11:00:00Z"))
                                        .cursor())
                                .isEqualTo(empty);
                    } finally {
                        release.countDown();
                    }
                    String a = first.get(10, TimeUnit.SECONDS);
                    String b = second.get(10, TimeUnit.SECONDS);
                    var page = replay.page(empty, null, 2000);
                    assertThat(page.entries())
                            .extracting(e -> e.event().id())
                            .containsExactlyElementsOf(rollback ? java.util.List.of(b) : java.util.List.of(a, b));
                    assertThat(jdbc.queryForObject("SELECT last_position FROM event_stream_state", Long.class))
                            .isEqualTo(rollback ? 1 : 2);
                    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM event_capture_receipts", Long.class))
                            .isEqualTo(rollback ? 0 : 1);
                    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM agent_sessions", Long.class))
                            .isEqualTo(rollback ? 1 : 2);
                    String cursor = replay.start(null, null).cursor();
                    StreamPositionStore.initialize(jdbc);
                    assertThat(new StreamReplayRepository(jdbc)
                                    .start(cursor, null)
                                    .reset())
                            .isFalse();
                    if (!rollback) {
                        var request = event("A", "2026-10-03T12:00:00Z");
                        assertThat(tx.execute(status -> store.persistIdempotentEvent(
                                                "capture-A", "hash-A", request, request.observedAt(), "A", 1))
                                        .replayed())
                                .isTrue();
                        assertThat(jdbc.queryForObject("SELECT last_position FROM event_stream_state", Long.class))
                                .isEqualTo(2);
                        var beforeBoundary = event("before-boundary", "2027-10-03T12:00:00.123456788Z");
                        var atBoundary = event("at-boundary", "2027-10-03T12:00:00.123456789Z");
                        tx.executeWithoutResult(
                                status -> store.persistEvent(beforeBoundary, beforeBoundary.observedAt(), "before", 1));
                        String boundaryId = tx.execute(
                                        status -> store.persistEvent(atBoundary, atBoundary.observedAt(), "at", 1))
                                .event()
                                .id();
                        Instant since = atBoundary.observedAt();
                        var recent = replay.page(replay.start(null, since).cursor(), since, 2000);
                        assertThat(recent.more()).isFalse();
                        assertThat(recent.entries()).hasSize(2);
                        assertThat(recent.entries().getFirst().event()).isNull();
                        assertThat(recent.entries().getLast().event().id()).isEqualTo(boundaryId);
                        assertThat(replay.start(null, Instant.parse("2030-01-01T00:00:00Z"))
                                        .cursor())
                                .isEqualTo(replay.start(null, null).cursor());
                    }
                } finally {
                    release.countDown();
                }
            } finally {
                try (var statement = admin.createStatement()) {
                    statement.execute("DROP SCHEMA " + schema + " CASCADE");
                }
            }
        }
    }

    private static EventIngestRequest event(String session, String timestamp) {

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
                Instant.parse(timestamp));
    }
}
