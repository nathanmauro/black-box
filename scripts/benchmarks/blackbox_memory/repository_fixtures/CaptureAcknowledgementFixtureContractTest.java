package dev.nathan.sbaagentic.recording.internal.application;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nathan.sbaagentic.recording.EventIngestRequest;
import dev.nathan.sbaagentic.recording.EventRecorded;
import dev.nathan.sbaagentic.recording.IngestResponse;
import dev.nathan.sbaagentic.recording.IngestionProperties;
import dev.nathan.sbaagentic.recording.SessionStopped;
import dev.nathan.sbaagentic.recording.internal.adapter.out.sqlite.EventFtsIndex;
import dev.nathan.sbaagentic.recording.internal.adapter.out.sqlite.RecordingSqlStore;
import dev.nathan.sbaagentic.recording.internal.application.port.RecordingStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Identical service-commit-boundary grader on both pinned revisions: real ingestion service, real
 * SQLite store behind its own transactional proxy, and a fixture only at optional publication.
 */
class CaptureAcknowledgementFixtureContractTest {
    private static final Instant BASE = Instant.parse("2026-10-03T12:00:00Z");
    private static Path ownedRoot;
    private String url;
    private JdbcTemplate jdbc;
    private FixturePublisher publisher;
    private EventIngestService service;

    @BeforeAll
    static void createOwnedRoot() throws IOException {
        Path target = Files.createDirectories(Path.of("target").toAbsolutePath());
        ownedRoot = Files.createTempDirectory(target, "capture-ack-fixture-");
        // Keep the SQLite JNI extraction in the same owned tree, not the user's temp settings.
        System.setProperty("org.sqlite.tmpdir", ownedRoot.toString());
    }

    @BeforeEach
    void createDatabase() throws IOException {
        Path directory = Files.createTempDirectory(ownedRoot, "case-");
        url = "jdbc:sqlite:" + directory.resolve("events.db");
        var source = new DriverManagerDataSource(url);
        jdbc = new JdbcTemplate(source);
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(source);
        Clock clock = Clock.fixed(BASE, ZoneOffset.UTC);
        var fts = new EventFtsIndex(jdbc, clock);
        fts.ensureFtsSchema();
        var store = new RecordingSqlStore(jdbc, new ObjectMapper(), clock, fts);
        store.ensureSchema();
        // The store's own @Transactional boundary, applied by the real Spring interceptor.
        var proxy = new ProxyFactory(store);
        proxy.addInterface(RecordingStore.class);
        proxy.addAdvice(new TransactionInterceptor(
                new DataSourceTransactionManager(source), new AnnotationTransactionAttributeSource()));
        publisher = new FixturePublisher();
        service = new EventIngestService(
                (RecordingStore) proxy.getProxy(),
                new IngestionProperties(),
                new RedactionService(new IngestionProperties()),
                publisher);
    }

    @AfterAll
    static void removeOwnedTree() throws IOException {
        if (ownedRoot == null)

            return;

        try (var paths = Files.walk(ownedRoot)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    @Test
    void optionalRecordedFailureAcknowledgesCommittedCapture() {
        publisher.failing = Set.of(EventRecorded.class);
        IngestResponse response = assertDoesNotThrow(() -> service.ingest(request("ack-ordinary", "Observation")));
        assertCanonical(response, "ack-ordinary", 1);
        assertEquals(List.of("EventRecorded"), publisher.attempts);
        publisher.assertEachSawCommittedEvent(response.eventId());
    }

    @Test
    void terminalStopIsAttemptedAfterRecordedFailure() {
        publisher.failing = Set.of(EventRecorded.class);
        IngestResponse response = assertDoesNotThrow(() -> service.ingest(request("ack-stop-recorded", "Stop")));
        assertEquals(List.of("EventRecorded", "SessionStopped"), publisher.attempts);
        assertCanonical(response, "ack-stop-recorded", 1);
        publisher.assertEachSawCommittedEvent(response.eventId());
    }

    @Test
    void terminalStopFailureAcknowledgesCommittedCapture() {
        publisher.failing = Set.of(SessionStopped.class);
        IngestResponse response = assertDoesNotThrow(() -> service.ingest(request("ack-stop-stopped", "Stop")));
        assertEquals(List.of("EventRecorded", "SessionStopped"), publisher.attempts);
        assertCanonical(response, "ack-stop-stopped", 1);
        publisher.assertEachSawCommittedEvent(response.eventId());
    }

    @Test
    void bothTerminalFailuresAcknowledgeAfterBothAttempts() {
        publisher.failing = Set.of(EventRecorded.class, SessionStopped.class);
        IngestResponse response = assertDoesNotThrow(() -> service.ingest(request("ack-stop-both", "Stop")));
        assertEquals(List.of("EventRecorded", "SessionStopped"), publisher.attempts);
        assertCanonical(response, "ack-stop-both", 1);
        publisher.assertEachSawCommittedEvent(response.eventId());
    }

    @Test
    void ordinaryCaptureAcknowledgementShapeAndAppendOnlyRetryRemain() {
        IngestResponse first = service.ingest(request("ack-normal", "Observation"));
        assertEquals("codex", first.source());
        assertEquals("ack-normal", first.clientSessionId());
        assertEquals("Observation", first.eventType());
        assertFalse(first.indexed());
        assertCanonical(first, "ack-normal", 1);
        assertEquals(List.of("EventRecorded"), publisher.attempts);
        publisher.assertEachSawCommittedEvent(first.eventId());

        IngestResponse retry = service.ingest(request("ack-normal", "Observation"));
        assertNotEquals(first.eventId(), retry.eventId());
        assertEquals(first.sessionId(), retry.sessionId());
        assertEquals(2, count("SELECT count(*) FROM agent_events WHERE client_session_id = ?", "ack-normal"));
        assertEquals(2, count("SELECT event_count FROM agent_sessions WHERE id = ?", first.sessionId()));
        assertEquals(List.of("EventRecorded", "EventRecorded"), publisher.attempts);
    }

    @Test
    void ordinaryTerminalCapturePublishesRecordedThenStopped() {
        IngestResponse response = service.ingest(request("ack-normal-stop", "Stop"));
        assertCanonical(response, "ack-normal-stop", 1);
        assertEquals(List.of("EventRecorded", "SessionStopped"), publisher.attempts);
        publisher.assertEachSawCommittedEvent(response.eventId());
    }

    @Test
    void rejectedPayloadRollsBackSessionAndPublishesNothing() {
        var invalid = new EventIngestRequest(
                "codex",
                "ack-invalid",
                null,
                "PostToolUse",
                "tool",
                "invalid payload",
                "/fixture/ack",
                "Fixture",
                new Object(),
                null,
                Map.of(),
                BASE);
        assertThrows(IllegalArgumentException.class, () -> service.ingest(invalid));
        assertNothingPersistedOrPublished("ack-invalid");
    }

    @Test
    void failedDatabaseWriteRollsBackSessionAndPublishesNothing() {
        jdbc.execute("CREATE TRIGGER reject_fixture_event BEFORE INSERT ON agent_events "
                + "WHEN NEW.client_session_id = 'ack-db-failure' "
                + "BEGIN SELECT RAISE(ABORT, 'fixture persistence failure'); END");
        assertThrows(DataAccessException.class, () -> service.ingest(request("ack-db-failure", "Observation")));
        assertNothingPersistedOrPublished("ack-db-failure");
    }

    private static EventIngestRequest request(String client, String eventType) {

        return new EventIngestRequest(
                " Codex ",
                client,
                null,
                eventType,
                "assistant",
                "Fixture capture " + client,
                "/fixture/ack",
                null,
                null,
                null,
                Map.of("fixture", "synthetic"),
                BASE);
    }

    private void assertCanonical(IngestResponse response, String client, int events) {
        assertEquals(events, count("SELECT count(*) FROM agent_events WHERE client_session_id = ?", client));
        assertEquals(
                response.eventId(),
                jdbc.queryForObject("SELECT id FROM agent_events WHERE client_session_id = ?", String.class, client));
        assertEquals(
                response.sessionId(),
                jdbc.queryForObject(
                        "SELECT session_id FROM agent_events WHERE id = ?", String.class, response.eventId()));
        assertEquals(events, count("SELECT event_count FROM agent_sessions WHERE id = ?", response.sessionId()));
    }

    private void assertNothingPersistedOrPublished(String client) {
        assertEquals(0, count("SELECT count(*) FROM agent_events WHERE client_session_id = ?", client));
        assertEquals(0, count("SELECT count(*) FROM agent_sessions WHERE client_session_id = ?", client));
        assertEquals(List.of(), publisher.attempts);
    }

    private int count(String sql, String value) {
        Integer result = jdbc.queryForObject(sql, Integer.class, value);

        return result == null ? -1 : result;
    }

    /** Optional fan-out only: records each attempt, checks commit visibility, then may throw. */
    private final class FixturePublisher implements ApplicationEventPublisher {
        Set<Class<?>> failing = Set.of();
        final List<String> attempts = new ArrayList<>();
        final List<String> observedEventIds = new ArrayList<>();
        final List<Boolean> committedVisible = new ArrayList<>();
        final List<Boolean> transactionActive = new ArrayList<>();

        @Override
        public void publishEvent(Object event) {
            String eventId = event instanceof EventRecorded recorded
                    ? recorded.event().id()
                    : event instanceof SessionStopped stopped ? stopped.event().id() : null;
            if (eventId == null)

                return;

            attempts.add(event.getClass().getSimpleName());
            observedEventIds.add(eventId);
            transactionActive.add(TransactionSynchronizationManager.isActualTransactionActive());
            committedVisible.add(independentlyVisible(eventId));
            if (failing.contains(event.getClass())) {
                throw new IllegalStateException(
                        "fixture optional " + event.getClass().getSimpleName() + " failure");
            }
        }

        void assertEachSawCommittedEvent(String eventId) {
            for (int i = 0; i < attempts.size(); i++) {
                assertEquals(eventId, observedEventIds.get(i));
                assertEquals(Boolean.TRUE, committedVisible.get(i), "event committed before " + attempts.get(i));
                assertEquals(Boolean.FALSE, transactionActive.get(i), "publication outside the store transaction");
            }
        }

        private boolean independentlyVisible(String eventId) {
            try (Connection connection = DriverManager.getConnection(url);
                    PreparedStatement statement = connection.prepareStatement(
                            "SELECT count(*) FROM agent_events e JOIN agent_sessions s ON s.id = e.session_id"
                                    + " WHERE e.id = ?")) {
                statement.setString(1, eventId);
                try (ResultSet rows = statement.executeQuery()) {

                    return rows.next() && rows.getInt(1) == 1;
                }
            } catch (SQLException ex) {

                return false;
            }
        }
    }
}
