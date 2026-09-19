package dev.nathan.sbaagentic.recording;

import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nathan.sbaagentic.SbaAgenticApplication;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IdempotentCaptureHttpTest {
    @TempDir static Path tempDir;
    private final TestRestTemplate http = new TestRestTemplate();
    private ServletWebServerApplicationContext app;
    private JdbcTemplate jdbc;
    private String base;

    @BeforeAll
    void start() {
        app = (ServletWebServerApplicationContext) new SpringApplicationBuilder(SbaAgenticApplication.class).run(
                "--spring.datasource.url=jdbc:sqlite:" + tempDir.resolve("capture.db"),
                "--server.address=127.0.0.1", "--server.port=0", "--sba.editor.enabled=false",
                "--sba.local-ai.enabled=false", "--sba.summary.backend=local",
                "--sba.elasticsearch.enabled=false", "--sba.memory.embedding.enabled=false",
                "--sba.ask.embedding-enabled=false", "--spring.main.banner-mode=off", "--logging.level.root=WARN");
        jdbc = app.getBean(JdbcTemplate.class);
        base = "http://127.0.0.1:" + app.getWebServer().getPort();
    }

    @AfterAll
    void stop() {
        if (app != null) app.close();
    }

    @Test
    void delayedEventsKeepLatestSessionActivity() {
        dev.nathan.sbaagentic.recording.SessionChronologyContract.delayedEvents(http, base, jdbc);
    }

    @Test
    void concurrentDistinctEventsConvergeOnLatestSessionActivity() throws Exception {
        dev.nathan.sbaagentic.recording.SessionChronologyContract.concurrentEvents(http, base, jdbc);
    }

    @Test
    void repeatedCaptureReturnsOriginalIdentityWithoutChangingSessionOrTimestamp() {
        String captureId = UUID.randomUUID().toString();
        var event = event("repeat-" + UUID.randomUUID());
        JsonNode first = post(captureId, event);
        Map<String, Object> session = jdbc.queryForMap("SELECT * FROM agent_sessions WHERE id = ?", first.path("sessionId").asText());
        JsonNode replay = post(captureId.toUpperCase(), event);
        assertThat(first.path("captureId").asText()).isEqualTo(captureId);
        assertThat(first.path("replayed").asBoolean()).isFalse();
        assertThat(first.has("indexed")).isFalse();
        assertThat(replay.path("replayed").asBoolean()).isTrue();
        assertSameIdentity(first, replay);
        assertThat(jdbc.queryForMap("SELECT * FROM agent_sessions WHERE id = ?", first.path("sessionId").asText())).isEqualTo(session);
        assertOneCapture(event.get("clientSessionId").toString(), captureId);
    }

    @Test
    void concurrentHttpDuplicatesPersistOneEventReceiptAndCount() throws Exception {
        String captureId = UUID.randomUUID().toString();
        var event = event("concurrent-" + UUID.randomUUID());
        try (var workers = Executors.newFixedThreadPool(8)) {
            CyclicBarrier barrier = new CyclicBarrier(8);
            var results = new ArrayList<java.util.concurrent.Future<JsonNode>>();
            for (int i = 0; i < 8; i++) {
                results.add(workers.submit(() -> { barrier.await(); return post(captureId, event); }));
            }
            List<JsonNode> acknowledgements = new ArrayList<>();
            for (var result : results) acknowledgements.add(result.get(15, TimeUnit.SECONDS));
            assertThat(acknowledgements.stream().filter(ack -> !ack.path("replayed").asBoolean()).count()).isEqualTo(1);
            assertThat(acknowledgements.stream().map(ack -> ack.path("eventId").asText()).distinct()).hasSize(1);
        }
        assertOneCapture(event.get("clientSessionId").toString(), captureId);
    }

    @Test
    void concurrentDifferentPayloadsChooseOneWinnerAndRejectTheOther() throws Exception {
        String captureId = UUID.randomUUID().toString();
        String client = "concurrent-conflict-" + UUID.randomUUID();
        var first = event(client);
        var second = event(client);
        second.put("text", "Different logical event");
        try (var workers = Executors.newFixedThreadPool(2)) {
            CyclicBarrier barrier = new CyclicBarrier(2);
            var a = workers.submit(() -> { barrier.await(); return http.postForEntity(base + "/api/events/idempotent", envelope(captureId, first), JsonNode.class); });
            var b = workers.submit(() -> { barrier.await(); return http.postForEntity(base + "/api/events/idempotent", envelope(captureId, second), JsonNode.class); });
            var responses = List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS));
            assertThat(responses.stream().map(response -> response.getStatusCode().value())).containsExactlyInAnyOrder(200, 409);
            assertThat(responses.stream().filter(response -> response.getStatusCode().value() == 409).findFirst().orElseThrow()
                    .getBody().path("error").path("type").asText()).isEqualTo("capture_id_conflict");
        }
        assertOneCapture(client, captureId);
    }

    @Test
    void digestSortsNestedObjectsButDetectsChangesBeforeRedaction() {
        String captureId = UUID.randomUUID().toString();
        var event = event("fingerprint-" + UUID.randomUUID());
        event.put("text", "password=first-secret-value");
        event.put("toolInput", Map.of("steps", List.of(Map.of("a", 1, "z", 2))));
        JsonNode first = post(captureId, event);
        Map<String, Object> reversed = new LinkedHashMap<>();
        reversed.put("z", 2);
        reversed.put("a", 1);
        event.put("toolInput", Map.of("steps", List.of(reversed)));
        event.put("unknownIgnoredField", "not part of the recognized event contract");
        assertThat(post(captureId, event).path("replayed").asBoolean()).isTrue();
        event.put("text", "password=second-secret-value");
        var conflict = http.postForEntity(base + "/api/events/idempotent", envelope(captureId, event), JsonNode.class);
        assertThat(conflict.getStatusCode().value()).isEqualTo(409);
        assertThat(conflict.getBody().path("error").path("type").asText()).isEqualTo("capture_id_conflict");
        assertThat(jdbc.queryForObject("SELECT text FROM agent_events WHERE id = ?", String.class, first.path("eventId").asText()))
                .isEqualTo("password=[REDACTED]");
        assertThat(jdbc.queryForObject("SELECT request_hash FROM event_capture_receipts WHERE capture_id = ?", String.class, captureId))
                .matches("[0-9a-f]{64}");
        assertOneCapture(event.get("clientSessionId").toString(), captureId);
    }

    @Test
    void sourceAndSessionNamespacesAreIndependentButNormalized() {
        String captureId = UUID.randomUUID().toString();
        String client = "namespace-" + UUID.randomUUID();
        var event = event(client);
        event.put("source", " Codex ");
        event.put("clientSessionId", " " + client + " ");
        JsonNode first = post(captureId, event);
        assertThat(post(captureId, event).path("replayed").asBoolean()).isTrue();
        event.put("source", "codex");
        event.put("clientSessionId", client);
        JsonNode normalizedReplay = post(captureId, event);
        assertThat(normalizedReplay.path("replayed").asBoolean()).isTrue();
        assertSameIdentity(first, normalizedReplay);
        event.put("source", "claude");
        JsonNode second = post(captureId, event);
        event.put("clientSessionId", client + "-other");
        JsonNode third = post(captureId, event);
        assertThat(List.of(first.path("eventId").asText(), second.path("eventId").asText(), third.path("eventId").asText()))
                .doesNotHaveDuplicates();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM event_capture_receipts WHERE capture_id = ?", Integer.class, captureId)).isEqualTo(3);
    }

    @Test
    void malformedRequestsDoNotReserveReceiptsOrCreateEvents() {
        long receipts = jdbc.queryForObject("SELECT count(*) FROM event_capture_receipts", Long.class);
        long events = jdbc.queryForObject("SELECT count(*) FROM agent_events", Long.class);
        var event = event("invalid-" + UUID.randomUUID());
        var bodies = List.of(Map.of("event", event), envelope("", event), envelope("not-a-uuid", event),
                envelope("1-1-1-1-1", event), Map.of("captureId", UUID.randomUUID().toString()),
                envelope(UUID.randomUUID().toString(), Map.of("source", "codex")));
        for (var body : bodies) {
            assertThat(http.postForEntity(base + "/api/events/idempotent", body, JsonNode.class).getStatusCode().value()).isEqualTo(400);
        }
        assertThat(http.postForEntity(base + "/api/events/idempotent", null, JsonNode.class).getStatusCode().value()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM event_capture_receipts", Long.class)).isEqualTo(receipts);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_events", Long.class)).isEqualTo(events);
    }

    @Test
    void failureAfterReservationRollsBackReceiptSessionAndEvent() {
        String captureId = UUID.randomUUID().toString();
        String client = "rollback-" + UUID.randomUUID();
        var event = event(client);
        jdbc.execute("CREATE TRIGGER reject_capture BEFORE INSERT ON agent_events WHEN NEW.client_session_id = '" + client
                + "' BEGIN SELECT RAISE(ABORT, 'controlled event failure'); END");
        try {
            var failed = http.postForEntity(base + "/api/events/idempotent", envelope(captureId, event), JsonNode.class);
            assertThat(failed.getStatusCode().value()).isEqualTo(500);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM event_capture_receipts WHERE capture_id = ?", Integer.class, captureId)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_sessions WHERE client_session_id = ?", Integer.class, client)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_events WHERE client_session_id = ?", Integer.class, client)).isZero();
        }
        finally {
            jdbc.execute("DROP TRIGGER reject_capture");
        }
        assertThat(post(captureId, event).path("replayed").asBoolean()).isFalse();
        assertOneCapture(client, captureId);
    }

    @Test
    void responseNotReadThenServerRestartStillReturnsOriginalIdentity() throws Exception {
        String captureId = UUID.randomUUID().toString();
        var event = event("lost-response-" + UUID.randomUUID());
        byte[] body = new ObjectMapper().writeValueAsBytes(envelope(captureId, event));
        // Deliver the request then close without reading any response bytes.
        try (Socket socket = new Socket("127.0.0.1", app.getWebServer().getPort())) {
            socket.getOutputStream().write(("POST /api/events/idempotent HTTP/1.1\r\nHost: localhost\r\n"
                    + "Content-Type: application/json\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().write(body);
            socket.getOutputStream().flush();
            socket.shutdownOutput();
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertOneCapture(event.get("clientSessionId").toString(), captureId));
        }
        String original = jdbc.queryForObject("SELECT event_id FROM event_capture_receipts WHERE capture_id = ?", String.class, captureId);
        app.close();
        start();
        JsonNode replay = post(captureId, event);
        assertThat(replay.path("eventId").asText()).isEqualTo(original);
        assertThat(replay.path("replayed").asBoolean()).isTrue();
        assertOneCapture(event.get("clientSessionId").toString(), captureId);
    }

    @Test
    void terminalReplayDoesNotRepublishAndOptionalFailureStillAcknowledgesStorage() {
        for (boolean failRecorded : List.of(false, true)) {
            String captureId = UUID.randomUUID().toString();
            String client = "terminal-" + UUID.randomUUID();
            AtomicInteger recorded = new AtomicInteger();
            AtomicInteger stopped = new AtomicInteger();
            ApplicationListener<PayloadApplicationEvent<?>> listener = published -> {
                if (published.getPayload() instanceof EventRecorded value && client.equals(value.event().clientSessionId())) {
                    recorded.incrementAndGet();
                    if (failRecorded) throw new IllegalStateException("controlled optional fanout failure");
                }
                if (published.getPayload() instanceof SessionStopped value && client.equals(value.event().clientSessionId())) stopped.incrementAndGet();
            };
            app.addApplicationListener(listener);
            var event = event(client);
            event.put("eventType", "Stop");
            JsonNode first = post(captureId, event);
            JsonNode replay = post(captureId, event);
            assertSameIdentity(first, replay);
            assertThat(replay.path("replayed").asBoolean()).isTrue();
            assertThat(recorded.get()).isEqualTo(1);
            assertThat(stopped.get()).isEqualTo(1);
            assertOneCapture(client, captureId);
        }
    }

    @Test
    void legacyEndpointStillCreatesSeparateEventsAndKeepsItsWireShape() {
        var event = event("legacy-" + UUID.randomUUID());
        var first = http.postForEntity(base + "/api/events", event, JsonNode.class);
        var second = http.postForEntity(base + "/api/events", event, JsonNode.class);
        assertThat(first.getStatusCode().value()).isEqualTo(200);
        assertThat(second.getStatusCode().value()).isEqualTo(200);
        assertThat(first.getBody().path("eventId").asText()).isNotEqualTo(second.getBody().path("eventId").asText());
        assertThat(first.getBody().has("indexed")).isTrue();
        assertThat(first.getBody().has("captureId")).isFalse();
        assertThat(first.getBody().has("replayed")).isFalse();
        assertThat(jdbc.queryForObject("SELECT event_count FROM agent_sessions WHERE client_session_id = ?", Integer.class, event.get("clientSessionId"))).isEqualTo(2);
    }

    @Test
    void receiptPreventsDeletingAcknowledgedEventAndRecreatingItsIdentity() {
        String captureId = UUID.randomUUID().toString();
        var event = event("retained-" + UUID.randomUUID());
        JsonNode first = post(captureId, event);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM agent_events WHERE id = ?", first.path("eventId").asText()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertSameIdentity(first, post(captureId, event));
        assertOneCapture(event.get("clientSessionId").toString(), captureId);
    }

    private JsonNode post(String captureId, Map<String, Object> event) {
        var response = http.postForEntity(base + "/api/events/idempotent", envelope(captureId, event), JsonNode.class);
        assertThat(response.getStatusCode().value()).as(String.valueOf(response.getBody())).isEqualTo(200);
        assertThat(response.getBody().path("eventId").asText()).isNotBlank();
        return response.getBody();
    }

    private static Map<String, Object> event(String clientSessionId) {
        return new LinkedHashMap<>(Map.of("source", "codex", "clientSessionId", clientSessionId,
                "eventType", "Observation", "text", "Durable receipt fixture"));
    }

    private static Map<String, Object> envelope(String captureId, Map<String, Object> event) {
        return Map.of("captureId", captureId, "event", event);
    }

    private void assertOneCapture(String client, String captureId) {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM event_capture_receipts WHERE capture_id = ?", Integer.class, captureId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_events WHERE client_session_id = ?", Integer.class, client)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT event_count FROM agent_sessions WHERE client_session_id = ?", Integer.class, client)).isEqualTo(1);
    }

    private static void assertSameIdentity(JsonNode first, JsonNode second) {
        assertThat(second.path("eventId")).isEqualTo(first.path("eventId"));
        assertThat(second.path("sessionId")).isEqualTo(first.path("sessionId"));
        assertThat(second.path("captureId")).isEqualTo(first.path("captureId"));
    }
}
