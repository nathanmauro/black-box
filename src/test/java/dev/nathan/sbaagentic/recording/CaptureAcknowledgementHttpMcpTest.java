package dev.nathan.sbaagentic.recording;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nathan.sbaagentic.SbaAgenticApplication;
import dev.nathan.sbaagentic.judgment.JudgmentProperties;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.jdbc.core.JdbcTemplate;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CaptureAcknowledgementHttpMcpTest {
    @TempDir
    static Path directory;

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();
    private final Map<String, FailurePlan> failures = new ConcurrentHashMap<>();
    private ServletWebServerApplicationContext app;
    private JdbcTemplate jdbc;
    private String base;
    private String mcpSession;
    private int rpcId;

    @BeforeAll
    void startDisposableRecorder() {
        app = (ServletWebServerApplicationContext) new SpringApplicationBuilder(SbaAgenticApplication.class)
                .run(
                        "--spring.config.location=classpath:/application.yml",
                        "--spring.profiles.active=default",
                        "--spring.datasource.url=jdbc:sqlite:" + directory.resolve("acknowledgement.db"),
                        "--server.address=127.0.0.1",
                        "--server.port=0",
                        "--server.shutdown=immediate",
                        "--sba.auth.enabled=false",
                        "--sba.editor.enabled=false",
                        "--sba.local-ai.enabled=false",
                        "--sba.summary.backend=local",
                        "--sba.elasticsearch.enabled=false",
                        "--sba.memory.embedding.enabled=false",
                        "--sba.ask.embedding-enabled=false",
                        "--sba.judge.enabled=false",
                        "--sba.schema.retire-workflow=false",
                        "--spring.main.banner-mode=off",
                        "--logging.level.root=WARN");
        assertThat(app.getBean(JudgmentProperties.class).isEnabled()).isFalse();
        jdbc = app.getBean(JdbcTemplate.class);
        base = "http://127.0.0.1:" + app.getWebServer().getPort();
        app.addApplicationListener((ApplicationListener<PayloadApplicationEvent<?>>) published -> {
            if (published.getPayload() instanceof EventRecorded value) {
                var plan = failures.get(value.event().clientSessionId());
                if (plan != null) {
                    plan.recorded.incrementAndGet();
                    if (plan.failRecorded) throw new IllegalStateException("fixture optional EventRecorded failure");
                }
            }
            if (published.getPayload() instanceof SessionStopped value) {
                var plan = failures.get(value.event().clientSessionId());
                if (plan != null) {
                    plan.stopped.incrementAndGet();
                    if (plan.failStopped) throw new IllegalStateException("fixture optional SessionStopped failure");
                }
            }
        });
    }

    @AfterAll
    void closeDisposableRecorder() {
        if (app != null) app.close();
        http.close();
    }

    @Test
    void restDecisionAcknowledgesCanonicalCommitAfterOptionalFailure() throws Exception {
        String client = client();
        var plan = plan(client, true, false);
        var response = post("/api/decisions", decision(client));
        assertThat(response.statusCode()).isEqualTo(200);
        assertCanonicalIdentity(client, json.readTree(response.body()));
        assertThat(plan.recorded.get()).isEqualTo(1);
        assertThat(plan.stopped.get()).isZero();
    }

    @Test
    void mcpDecisionAcknowledgesCanonicalCommitAfterOptionalFailure() throws Exception {
        initializeMcp();
        String client = client();
        var plan = plan(client, true, false);
        var envelope = callDecision(decision(client));
        assertThat(isToolError(envelope)).as(envelope.toString()).isFalse();
        JsonNode acknowledgement = json.readTree(
                envelope.path("result").path("content").get(0).path("text").asText());
        assertCanonicalIdentity(client, acknowledgement);
        assertThat(plan.recorded.get()).isEqualTo(1);
    }

    @ParameterizedTest
    @CsvSource({"true,false", "false,true", "true,true"})
    void terminalNotificationsAreAttemptedIndependentlyAndCommitIsAcknowledged(
            boolean failRecorded, boolean failStopped) throws Exception {
        String client = client();
        var plan = plan(client, failRecorded, failStopped);
        var response = post(
                "/api/events",
                Map.of(
                        "source",
                        "codex",
                        "clientSessionId",
                        client,
                        "eventType",
                        "Stop",
                        "role",
                        "assistant",
                        "text",
                        "Fixture terminal capture"));
        assertThat(response.statusCode()).isEqualTo(200);
        assertCanonicalIdentity(client, json.readTree(response.body()));
        assertThat(plan.recorded.get()).isEqualTo(1);
        assertThat(plan.stopped.get()).isEqualTo(1);
    }

    @Test
    void validationFailuresRemainErrorsWithoutPersistingOrPublishing() throws Exception {
        initializeMcp();
        for (boolean mcp : List.of(false, true)) {
            String client = client();
            var plan = plan(client, false, false);
            var invalid = new LinkedHashMap<>(decision(client));
            invalid.put("decision", " ");
            if (mcp) assertThat(isToolError(callDecision(invalid))).isTrue();
            else assertThat(post("/api/decisions", invalid).statusCode()).isEqualTo(400);
            assertNotPersistedOrPublished(client, plan);
        }
    }

    @Test
    void databaseFailureRollsBackAndRemainsAnErrorOnRestAndMcp() throws Exception {
        initializeMcp();
        String prefix = "reject-" + UUID.randomUUID();
        jdbc.execute(
                "CREATE TRIGGER reject_acknowledgement BEFORE INSERT ON agent_events WHEN NEW.client_session_id LIKE '"
                        + prefix + "%' BEGIN SELECT RAISE(ABORT, 'fixture persistence failure'); END");
        try {
            for (boolean mcp : List.of(false, true)) {
                String client = prefix + mcp;
                var plan = plan(client, false, false);
                if (mcp) assertThat(isToolError(callDecision(decision(client)))).isTrue();
                else
                    assertThat(post("/api/decisions", decision(client)).statusCode())
                            .isEqualTo(500);
                assertNotPersistedOrPublished(client, plan);
            }
        } finally {
            jdbc.execute("DROP TRIGGER reject_acknowledgement");
        }
    }

    @Test
    void legacyRetriesStillAppendSeparateEvents() throws Exception {
        String client = client();
        var plan = plan(client, true, false);
        var first = post("/api/decisions", decision(client));
        var retry = post("/api/decisions", decision(client));
        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(retry.statusCode()).isEqualTo(200);
        assertThat(json.readTree(first.body()).path("eventId"))
                .isNotEqualTo(json.readTree(retry.body()).path("eventId"));
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM agent_events WHERE client_session_id=?", Integer.class, client))
                .isEqualTo(2);
        assertThat(plan.recorded.get()).isEqualTo(2);
    }

    private void assertCanonicalIdentity(String client, JsonNode acknowledgement) {
        assertThat(acknowledgement.path("eventId").asText()).isNotBlank();
        assertThat(acknowledgement.has("indexed")).isTrue();
        assertThat(acknowledgement.has("replayed")).isFalse();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM agent_events WHERE client_session_id=?", Integer.class, client))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT id FROM agent_events WHERE client_session_id=?", String.class, client))
                .isEqualTo(acknowledgement.path("eventId").asText());
        assertThat(jdbc.queryForObject(
                        "SELECT event_count FROM agent_sessions WHERE id=?",
                        Integer.class,
                        acknowledgement.path("sessionId").asText()))
                .isEqualTo(1);
    }

    private void assertNotPersistedOrPublished(String client, FailurePlan plan) {
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM agent_events WHERE client_session_id=?", Integer.class, client))
                .isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM agent_sessions WHERE client_session_id=?", Integer.class, client))
                .isZero();
        assertThat(plan.recorded.get()).isZero();
        assertThat(plan.stopped.get()).isZero();
    }

    private FailurePlan plan(String client, boolean recorded, boolean stopped) {
        var plan = new FailurePlan(recorded, stopped);
        failures.put(client, plan);

        return plan;
    }

    private static String client() {

        return "ack-fixture-" + UUID.randomUUID();
    }

    private static Map<String, Object> decision(String client) {

        return Map.of(
                "source",
                "codex",
                "clientSessionId",
                client,
                "repo",
                "/fixture/acknowledgement",
                "decision",
                "Use fixture storage",
                "rationale",
                "Controlled fixture",
                "alternatives",
                List.of(),
                "confidence",
                0.8,
                "openLoops",
                List.of());
    }

    private void initializeMcp() throws Exception {
        if (mcpSession != null)

            return;

        var initialized = rpc(
                "initialize",
                Map.of(
                        "protocolVersion",
                        "2024-11-05",
                        "capabilities",
                        Map.of(),
                        "clientInfo",
                        Map.of("name", "acknowledgement-fixture", "version", "1")));
        assertThat(initialized.has("error")).isFalse();
        assertThat(post("/mcp", Map.of("jsonrpc", "2.0", "method", "notifications/initialized"))
                        .statusCode())
                .isBetween(200, 299);
    }

    private JsonNode callDecision(Map<String, Object> arguments) throws Exception {

        return rpc("tools/call", Map.of("name", "captureDecision", "arguments", arguments));
    }

    private static boolean isToolError(JsonNode envelope) {

        return envelope.has("error") || envelope.path("result").path("isError").asBoolean();
    }

    private JsonNode rpc(String method, Map<String, Object> params) throws Exception {
        int id = ++rpcId;
        var response = post("/mcp", Map.of("jsonrpc", "2.0", "id", id, "method", method, "params", params));
        assertThat(response.statusCode()).isEqualTo(200);
        String body = response.body();
        if (response.headers().firstValue("Content-Type").orElse("").contains("text/event-stream")) {
            body = body.lines()
                    .filter(line -> line.startsWith("data:"))
                    .map(line -> line.substring(5).strip())
                    .findFirst()
                    .orElseThrow();
        }
        JsonNode envelope = json.readTree(body);
        assertThat(envelope.path("id").asInt()).isEqualTo(id);

        return envelope;
    }

    private HttpResponse<String> post(String path, Map<String, Object> body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream");
        if (path.equals("/mcp") && mcpSession != null) request.header("Mcp-Session-Id", mcpSession);
        var response = http.send(
                request.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        if (path.equals("/mcp")) response.headers().firstValue("Mcp-Session-Id").ifPresent(value -> mcpSession = value);

        return response;
    }

    private static final class FailurePlan {
        final boolean failRecorded;
        final boolean failStopped;
        final AtomicInteger recorded = new AtomicInteger();
        final AtomicInteger stopped = new AtomicInteger();

        FailurePlan(boolean recorded, boolean stopped) {
            failRecorded = recorded;
            failStopped = stopped;
        }
    }
}
