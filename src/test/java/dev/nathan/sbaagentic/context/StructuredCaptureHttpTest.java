package dev.nathan.sbaagentic.context;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import static org.assertj.core.api.Assertions.assertThat;

/** Real Streamable HTTP capture/error/recall paths against an isolated relational store. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:sqlite:${java.io.tmpdir}/bb-capture-http-${random.uuid}.db",
        "sba.local-ai.enabled=false",
        "sba.elasticsearch.enabled=false",
        "sba.memory.embedding.enabled=false",
        "sba.summary.backend=local"
})
class StructuredCaptureHttpTest {

    @LocalServerPort
    int port;

    @Autowired
    ObjectMapper mapper;

    private final HttpClient client = HttpClient.newHttpClient();
    private String session;
    private int requestId;

    @Test
    void missingNullAndBlankRequiredCaptureFieldsReturnActionableErrorsWithoutWriting() throws Exception {
        initialize();
        long before = eventCount();
        for (String tool : List.of("captureHandoff", "captureDecision", "captureObservation", "captureProjection")) {
            List<String> required = switch (tool) {
                case "captureHandoff" -> List.of("source", "clientSessionId", "contextSummary");
                case "captureDecision" -> List.of("source", "clientSessionId", "decision");
                case "captureObservation" -> List.of("source", "clientSessionId", "text");
                default -> List.of("source", "clientSessionId");
            };
            for (String field : required) {
                for (int variant = 0; variant < 3; variant++) {
                    Map<String, Object> args = validArguments(tool);
                    if (variant == 0) args.remove(field);
                    else args.put(field, variant == 1 ? null : " \n\t ");
                    JsonNode response = call(tool, args);
                    assertThat(response.path("isError").asBoolean()).as("%s %s variant %s: %s", tool, field, variant, response).isTrue();
                    assertThat(response.toString()).contains(field + " must not be blank")
                            .doesNotContain("NullPointerException", "java.lang.", "StructuredCaptureService");
                    assertThat(eventCount()).as("invalid captures must not write events").isEqualTo(before);
                }
            }
        }
    }

    @Test
    void validHandoffRetainsStructuredFieldsAcrossMcpAndHttpRecall() throws Exception {
        initialize();
        long before = eventCount();
        Map<String, Object> args = validArguments("captureHandoff");
        JsonNode captured = textJson(call("captureHandoff", args));
        String eventId = captured.path("eventId").asText();
        assertThat(eventId).isNotBlank();
        assertThat(eventCount()).isEqualTo(before + 1);
        JsonNode recalled = textJson(call("recallContext", Map.of("repoOrTopic", eventId, "kinds", List.of("handoff"))));
        assertHandoff(recalled.path("items").get(0), eventId);
        JsonNode httpRecall = get("/api/recall?scope=" + eventId + "&kinds=handoff");
        assertHandoff(httpRecall.path("items").get(0), eventId);
    }

    private void assertHandoff(JsonNode item, String eventId) {
        assertThat(item.path("eventId").asText()).isEqualTo(eventId);
        assertThat(item.path("source").asText()).isEqualTo("manual");
        assertThat(item.path("headline").asText()).isEqualTo("Verified the synthetic checkpoint");
        assertThat(item.path("repo").asText()).isEqualTo("/fixture/capture-contract");
        assertThat(item.path("clientSessionId").asText()).isEqualTo("capture-http-fixture");
        assertThat(item.path("toAgent").asText()).isEqualTo("next-session");
        assertThat(item.path("nextAction").asText()).isEqualTo("Inspect the saved checkpoint");
        assertThat(item.path("openLoops").get(0).asText()).isEqualTo("User review remains");
    }

    private Map<String, Object> validArguments(String tool) {
        Map<String, Object> args = new LinkedHashMap<>(Map.of(
                "source", "manual", "clientSessionId", "capture-http-fixture", "repo", "/fixture/capture-contract"));
        switch (tool) {
            case "captureHandoff" -> args.putAll(Map.of("contextSummary", "Verified the synthetic checkpoint",
                    "toAgent", "next-session", "openLoops", List.of("User review remains"),
                    "nextAction", "Inspect the saved checkpoint"));
            case "captureDecision" -> args.put("decision", "Keep the fixture isolated");
            case "captureObservation" -> args.put("text", "Synthetic observation");
            case "captureProjection" -> args.put("paths", List.of(Map.of("title", "Review the fixture")));
            default -> throw new IllegalArgumentException(tool);
        }
        return args;
    }

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

    private long eventCount() throws Exception {
        JsonNode count = get("/api/status").path("storage").path("events");
        assertThat(count.isIntegralNumber()).as("status must provide a real event count").isTrue();
        return count.asLong();
    }

    private JsonNode get(String path) throws Exception {
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(15)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return mapper.readTree(response.body());
    }
}
