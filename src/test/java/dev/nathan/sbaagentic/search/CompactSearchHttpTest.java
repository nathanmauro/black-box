package dev.nathan.sbaagentic.search;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nathan.sbaagentic.recording.EventRecorder;
import dev.nathan.sbaagentic.recording.EventIngestRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:sqlite:${java.io.tmpdir}/bb-compact-http-${random.uuid}.db",
        "sba.local-ai.enabled=false", "sba.elasticsearch.enabled=false",
        "sba.memory.embedding.enabled=false", "sba.summary.backend=local"
})
class CompactSearchHttpTest {
    @LocalServerPort int port;
    @Autowired EventRecorder recorder;
    @Autowired ObjectMapper mapper;
    private final HttpClient client = HttpClient.newHttpClient();
    private String session;
    private int requestId;

    @Test
    void legacyRowLimitDoesNotBoundNestedPayload() throws Exception {
        String marker = "compact-baseline-nested";
        Map<String, String> nested = new java.util.LinkedHashMap<>();
        for (int i = 0; i < 30; i++) nested.put("part-" + i, "x".repeat(40_000));
        recorder.ingest(new EventIngestRequest("manual", marker, null, "PostToolUse", "tool",
                marker, "/fixture/recovery", "Read", null, Map.of("nested", nested),
                Map.of("rawHook", Map.of("nested", nested)), Instant.now()));
        var response = get("/api/search?q=" + marker + "&limit=1");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(mapper.readTree(response.body()).path("local")).hasSize(1);
        assertThat(response.body().getBytes(StandardCharsets.UTF_8).length).isGreaterThan(2_000_000);
        System.out.println("Synthetic legacy HTTP search bytes=" + response.body().getBytes(StandardCharsets.UTF_8).length);
        var compact = get("/api/search/compact?q=" + marker + "&maxBytes=2048");
        assertThat(compact.statusCode()).isEqualTo(200);
        assertThat(compact.body().getBytes(StandardCharsets.UTF_8)).hasSizeLessThanOrEqualTo(2048);
        assertThat(compact.body()).doesNotContain("rawHook", "toolOutputJson", "part-29");
        assertThat(mapper.readTree(compact.body()).path("items")).hasSize(1);
        initialize();
        var rpc = call("searchContext", Map.of("query", marker, "maxBytes", 2048));
        String application = rpc.path("content").get(0).path("text").asText();
        assertThat(application.getBytes(StandardCharsets.UTF_8)).hasSizeLessThanOrEqualTo(2048);
        assertThat(textJson(rpc).path("items").get(0).path("eventId").asText())
                .isEqualTo(mapper.readTree(compact.body()).path("items").get(0).path("eventId").asText());
        System.out.println("Synthetic compact HTTP bytes=" + compact.body().getBytes(StandardCharsets.UTF_8).length
                + ", MCP application bytes=" + application.getBytes(StandardCharsets.UTF_8).length);
    }


    @Test
    void recoveryFindsUserSourceAmongCopiesAndKeepsInferenceUnknown() throws Exception {
        String key = "recovery-origin-fixture";
        String task = "11111111-1111-4111-8111-111111111111";
        String clientId = "codex-voice:fixture:" + task + ":22222222-2222-4222-8222-222222222222";
        String origin = seed("codex", clientId, "UserPromptSubmit", "user", key + ": one authoritative backend",
                "2026-08-17T12:00:00Z");
        String proposal = seed("codex", "proposal", "AssistantMessage", "assistant", key + ": engine A is a proposal only",
                "2026-08-17T13:00:00Z");
        for (int i = 0; i < 20; i++) seed("codex", "monitor-" + i, "PostToolUse", "tool",
                key + ": monitoring copy of the discussion", "2026-08-19T12:00:00Z");
        JsonNode found = mapper.readTree(get("/api/search/compact?q=" + key).body());
        assertThat(found.path("items")).hasSize(3);
        assertThat(found.path("items").findValuesAsText("eventId")).contains(origin, proposal);
        assertThat(found.path("items").get(0).path("provenance").asText()).isEqualTo("similar_observer_text_origin_unknown");
        assertThat(found.path("items").get(0).path("similarCount").asInt()).isEqualTo(20);
        JsonNode original = found.path("items").findValues("sourceReference").stream()
                .filter(item -> task.equals(item.path("externalTaskId").asText())).findFirst().orElseThrow();
        assertThat(original.path("externalStatus").asText()).isEqualTo("candidate_requires_verification");
        assertThat(mapper.readTree(get(original.path("eventPath").asText()).body()).path("id").asText()).isEqualTo(origin);
        var timed = get("/api/search/compact?q=" + encode(key + " until:2026-08-17"));
        assertThat(mapper.readTree(timed.body()).path("items")).hasSize(2);
        assertThat(mapper.readTree(timed.body()).path("coverage").path("elastic").path("status").asText()).isEqualTo("skipped_filters");
        var ungrouped = mapper.readTree(get("/api/search/compact?q=" + key + "&groupSimilar=false&limit=50").body());
        assertThat(ungrouped.path("items")).hasSize(22);
    }

    @Test
    void unsupportedDatesAreDiagnosedAndQuotedTokensRemainLiteral() throws Exception {
        seed("manual", "literal", "Observation", "assistant", "literal-date before:2026-08-18", "2026-08-17T12:00:00Z");
        var invalid = get("/api/search/compact?q=" + encode("literal-date before:2026-08-18"));
        assertThat(invalid.statusCode()).isEqualTo(400);
        assertThat(invalid.body()).contains("Unsupported before:");
        assertThat(get("/api/search/compact?q=" + encode("since:2026-02-30")).statusCode()).isEqualTo(400);
        var literal = get("/api/search/compact?q=" + encode("literal-date \"before:2026-08-18\""));
        assertThat(literal.statusCode()).isEqualTo(200);
        assertThat(mapper.readTree(literal.body()).path("items")).hasSize(1);
        initialize();
        assertThat(textJson(call("searchContext", Map.of("query", "before:2026-08-18"))).path("status").asText())
                .isEqualTo("invalid_query");
    }

    @Test
    void exactTimeBoundaryExcludesLaterFractionalSeconds() throws Exception {
        String key = "compact-time-edge";
        String equal = seed("manual", "edge-a", "Observation", "assistant", key, "2026-08-18T00:00:00Z");
        String later = seed("manual", "edge-b", "Observation", "assistant", key, "2026-08-18T00:00:00.100Z");
        var result = mapper.readTree(get("/api/search/compact?q=" + encode(key + " until:2026-08-18T00:00:00Z")).body());
        assertThat(result.path("items").findValuesAsText("eventId")).containsExactly(equal).doesNotContain(later);
    }

    @Test
    void exclusionRunsBeforeCandidateLimitAndExhaustionIsExplicit() throws Exception {
        String key = "compact-exclusion-fixture";
        String origin = seed("manual", "retained", "UserPromptSubmit", "user", key, "2026-08-17T00:00:00Z");
        for (int i = 0; i < 201; i++) seed("manual", "self-session", "PostToolUse", "tool", key, "2026-08-19T00:00:00Z");
        var capped = mapper.readTree(get("/api/search/compact?q=" + key).body());
        assertThat(capped.path("coverage").path("local").path("candidateLimitReached").asBoolean()).isTrue();
        var excluded = mapper.readTree(get("/api/search/compact?q=" + key + "&excludeSession=self-session").body());
        assertThat(excluded.path("items").findValuesAsText("eventId")).containsExactly(origin);
        assertThat(excluded.path("coverage").path("local").path("candidateLimitReached").asBoolean()).isFalse();
    }

    private String seed(String source, String sessionId, String kind, String role, String text, String time) {
        return recorder.ingest(new EventIngestRequest(source, sessionId, null, kind, role, text,
                "/fixture/recovery", null, null, null, Map.of(), Instant.parse(time))).eventId();
    }

    private static String encode(String text) { return URLEncoder.encode(text, StandardCharsets.UTF_8); }

    private void initialize() throws Exception {
        rpc("initialize", Map.of("protocolVersion", "2024-11-05", "capabilities", Map.of(),
                "clientInfo", Map.of("name", "capture-contract", "version", "1")));
        post(Map.of("jsonrpc", "2.0", "method", "notifications/initialized"));
    }

    private JsonNode call(String name, Map<String, Object> args) throws Exception {
        return rpc("tools/call", Map.of("name", name, "arguments", args));
    }

    private JsonNode rpc(String method, Map<String, Object> params) throws Exception {
        HttpResponse<String> response = post(Map.of("jsonrpc", "2.0", "id", ++requestId, "method", method, "params", params));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        String body = response.body();
        if (response.headers().firstValue("Content-Type").orElse("").contains("text/event-stream")) {
            body = body.lines().filter(line -> line.startsWith("data:")).map(line -> line.substring(5).strip()).findFirst().orElseThrow();
        }
        JsonNode envelope = mapper.readTree(body);
        assertThat(envelope.has("error")).as(envelope.toString()).isFalse();
        return envelope.path("result");
    }

    private HttpResponse<String> post(Map<String, Object> body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp"))
                .timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream");
        if (session != null) request.header("Mcp-Session-Id", session);
        HttpResponse<String> response = client.send(request.POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
        response.headers().firstValue("Mcp-Session-Id").ifPresent(value -> session = value);
        return response;
    }

    private JsonNode textJson(JsonNode result) throws Exception {
        assertThat(result.path("isError").asBoolean()).as(result.toString()).isFalse();
        return mapper.readTree(result.path("content").get(0).path("text").asText());
    }


    private HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
