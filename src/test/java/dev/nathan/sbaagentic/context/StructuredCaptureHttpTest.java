package dev.nathan.sbaagentic.context;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/** Real Streamable HTTP capture/error/recall paths against an isolated relational store. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.datasource.url=jdbc:sqlite:${java.io.tmpdir}/bb-capture-http-${random.uuid}.db",
            "sba.local-ai.enabled=false",
            "sba.elasticsearch.enabled=false",
            "sba.memory.embedding.enabled=false",
            "sba.summary.backend=local",
            "sba.judge.enabled=false",
            "sba.ingestion.redact-enabled=true",
            "sba.ingestion.max-text-length=20000"
        })
class StructuredCaptureHttpTest {

    @LocalServerPort
    int port;

    @Autowired
    ObjectMapper mapper;

    private final HttpClient client = HttpClient.newHttpClient();
    private String session;
    private int requestId;

    @AfterEach
    void closeHttpClient() {
        client.close();
    }

    @Test
    void missingNullAndBlankRequiredCaptureFieldsReturnActionableErrorsWithoutWriting() throws Exception {
        initialize();
        long before = eventCount();
        for (String tool : List.of("captureHandoff", "captureDecision", "captureObservation", "captureProjection")) {
            List<String> required =
                    switch (tool) {
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
                    assertThat(response.path("isError").asBoolean())
                            .as("%s %s variant %s: %s", tool, field, variant, response)
                            .isTrue();
                    assertThat(response.toString())
                            .contains(field + " must not be blank")
                            .doesNotContain("NullPointerException", "java.lang.", "StructuredCaptureService");
                    assertThat(eventCount())
                            .as("invalid captures must not write events")
                            .isEqualTo(before);
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
        JsonNode recalled =
                textJson(call("recallContext", Map.of("repoOrTopic", eventId, "kinds", List.of("handoff"))));
        assertHandoff(recalled.path("items").get(0), eventId);
        JsonNode httpRecall = get("/api/recall?scope=" + eventId + "&kinds=handoff");
        assertHandoff(httpRecall.path("items").get(0), eventId);
    }

    @Test
    void multilineObservationRetainsItsBodyAcrossMcpHttpAndCanonicalEvidence() throws Exception {
        initialize();
        String body = "Fixture migration check\nVerification failed: preserve the old database.\n"
                + "Next: investigate the foreign-key error before proceeding.";
        Map<String, Object> args = validArguments("captureObservation");
        args.put("text", body);
        String eventId =
                textJson(call("captureObservation", args)).path("eventId").asText();
        JsonNode http = get("/api/recall?scope=" + eventId + "&kinds=observation");
        JsonNode mcp = textJson(call("recallContext", Map.of("repoOrTopic", eventId, "kinds", List.of("observation"))));
        for (JsonNode recalled : List.of(http, mcp)) {
            assertThat(recalled.path("count").asInt()).isEqualTo(1);
            assertThat(recalled.path("truncated").asBoolean()).isFalse();
            JsonNode item = recalled.path("items").get(0);
            assertThat(item.path("headline").asText()).isEqualTo("Fixture migration check");
            assertThat(item.path("body").asText()).isEqualTo(body);
            assertThat(item.path("eventId").asText()).isEqualTo(eventId);
            assertThat(item.path("sessionId").asText()).isNotBlank();
        }
        assertThat(get("/api/events/" + eventId).path("text").asText()).isEqualTo(body);
    }

    @Test
    void oversizedObservationIsExplicitlyBoundedInMcpWhileHttpAndEvidenceStayComplete() throws Exception {
        initialize();
        String body = "Large fixture observation\n" + "Supporting evidence 🧪. ".repeat(400);
        Map<String, Object> args = validArguments("captureObservation");
        args.put("text", body);
        String eventId =
                textJson(call("captureObservation", args)).path("eventId").asText();
        JsonNode mcp = textJson(call(
                "recallContext", Map.of("repoOrTopic", eventId, "kinds", List.of("observation"), "maxChars", 700)));
        assertThat(mcp.path("count").asInt()).isEqualTo(1);
        assertThat(mcp.path("truncated").asBoolean()).isTrue();
        JsonNode item = mcp.path("items").get(0);
        assertThat(item.path("headline").asText()).isEqualTo("Large fixture observation");
        assertThat(item.path("body").asText())
                .startsWith("Large fixture observation\n")
                .contains("… (+")
                .hasSizeLessThan(700);
        assertThat(item.path("eventId").asText()).isEqualTo(eventId);
        assertThat(item.path("sessionId").asText()).isNotBlank();
        assertThat(get("/api/recall?scope=" + eventId + "&kinds=observation")
                        .path("items")
                        .get(0)
                        .path("body")
                        .asText())
                .isEqualTo(body);
        assertThat(get("/api/events/" + eventId).path("text").asText()).isEqualTo(body);
    }

    @ParameterizedTest
    @ValueSource(strings = {"http", "mcp"})
    void projectionRecallPreservesEveryFutureAndItsConditions(String transport) throws Exception {
        initialize();
        Map<String, Object> args = projectionArguments("Only after recovery checks pass");
        String eventId =
                textJson(call("captureProjection", args)).path("eventId").asText();
        JsonNode canonical = get("/api/events/" + eventId);
        String expected = "Projected futures:\n"
                + "1. Ship migration — Only after recovery checks pass (confidence: 0.6)\n"
                + "2. Defer migration — Keep current storage if recovery checks fail (confidence: 0.4)\n\n"
                + "Basis: Recovery must be proven before migration";
        assertThat(canonical.path("text").asText()).isEqualTo(expected);
        assertThat(canonical.path("metadata").path("paths")).hasSize(2);
        JsonNode recalled = transport.equals("http")
                ? get("/api/recall?scope=" + eventId + "&kinds=projection")
                : textJson(call("recallContext", Map.of("repoOrTopic", eventId, "kinds", List.of("projection"))));
        assertThat(recalled.path("count").asInt()).isEqualTo(1);
        assertThat(recalled.path("mode").asText()).isEqualTo("lexical");
        assertThat(recalled.path("truncated").asBoolean()).isFalse();
        JsonNode item = recalled.path("items").get(0);
        assertThat(item.path("body").asText())
                .as(transport + " projection evidence")
                .isEqualTo(expected);
        assertProjectionIdentity(item, canonical);
        // These compatibility summaries describe the first listed path, not a selected future.
        assertThat(item.path("headline").asText()).isEqualTo("Ship migration");
        assertThat(item.path("confidence").asDouble()).isEqualTo(0.6);
        assertThat(get("/api/recall?scope=" + eventId).path("count").asInt()).isZero();
        assertThat(textJson(call("recallContext", Map.of("repoOrTopic", eventId)))
                        .path("count")
                        .asInt())
                .isZero();
    }

    @Test
    void projectionMcpBudgetClipsRenderedBodyAndPreservesCanonicalEvidence() throws Exception {
        initialize();
        String eventId = textJson(call("captureProjection", projectionArguments("Recovery evidence 🧪. ".repeat(400))))
                .path("eventId")
                .asText();
        JsonNode canonical = get("/api/events/" + eventId);
        String stored = canonical.path("text").asText();
        assertThat(stored).contains("2. Defer migration", "Basis: Recovery must be proven before migration");
        JsonNode mcp = textJson(
                call("recallContext", Map.of("repoOrTopic", eventId, "kinds", List.of("projection"), "maxChars", 700)));
        assertThat(mcp.path("count").asInt()).isEqualTo(1);
        assertThat(mcp.path("truncated").asBoolean()).isTrue();
        JsonNode item = mcp.path("items").get(0);
        assertProjectionIdentity(item, canonical);
        String body = item.path("body").asText();
        int suffix = body.lastIndexOf("… (+");
        assertThat(suffix).isPositive();
        String prefix = body.substring(0, suffix);
        assertThat(stored).startsWith(prefix);
        assertThat(Character.isHighSurrogate(prefix.charAt(prefix.length() - 1)))
                .isFalse();
        assertThat(body.substring(suffix)).isEqualTo("… (+" + (stored.length() - prefix.length()) + " chars)");
        assertThat(body.length()
                        + item.path("headline").asText().length()
                        + item.path("rationale").asText().length()
                        + 200)
                .isLessThanOrEqualTo(700);
        assertThat(get("/api/recall?scope=" + eventId + "&kinds=projection")
                        .path("items")
                        .get(0)
                        .path("body")
                        .asText())
                .isEqualTo(stored);
        assertThat(get("/api/events/" + eventId)).isEqualTo(canonical);
    }

    @Test
    void projectionBodyKeepsIngestCappingDistinctFromFullPathMetadataAndMcpClipping() throws Exception {
        initialize();
        String description = "Recovery evidence. ".repeat(1300);
        Map<String, Object> args = projectionArguments(description);
        String eventId =
                textJson(call("captureProjection", args)).path("eventId").asText();
        JsonNode canonical = get("/api/events/" + eventId);
        String stored = canonical.path("text").asText();
        assertThat(stored).endsWith("\n[truncated]").doesNotContain("2. Defer migration");
        assertThat(canonical
                        .path("metadata")
                        .path("paths")
                        .get(0)
                        .path("description")
                        .asText())
                .isEqualTo(description.strip());
        assertThat(canonical
                        .path("metadata")
                        .path("paths")
                        .get(1)
                        .path("description")
                        .asText())
                .isEqualTo("Keep current storage if recovery checks fail");
        for (JsonNode recalled : List.of(
                get("/api/recall?scope=" + eventId + "&kinds=projection"),
                textJson(call(
                        "recallContext",
                        Map.of("repoOrTopic", eventId, "kinds", List.of("projection"), "maxChars", 50000))))) {
            assertThat(recalled.path("truncated").asBoolean()).isFalse();
            JsonNode item = recalled.path("items").get(0);
            assertProjectionIdentity(item, canonical);
            assertThat(item.path("body").asText()).isEqualTo(stored);
        }
        assertThat(get("/api/events/" + eventId)).isEqualTo(canonical);
    }

    @ParameterizedTest
    @ValueSource(strings = {"http", "mcp"})
    void ideaQuoteKeepsWhitespaceAcrossCaptureRevisionAndRecall(String transport) throws Exception {
        initialize();
        String key = UUID.randomUUID().toString();
        String quote = "    if ready:\n\treturn result 🐈  \n";
        Map<String, Object> args = ideaArguments(key);
        args.put("quote", quote);
        String firstId = captureIdea(transport, args).path("eventId").asText();
        JsonNode original = assertIdeaQuote(key, firstId, quote, 1);
        int revisions = 1;
        for (String omitted : List.of("omitted", "null", "empty", "blank")) {
            var revision = ideaArguments(key);
            revision.put("status", "tracked");
            switch (omitted) {
                case "null" -> revision.put("quote", null);
                case "empty" -> revision.put("quote", "");
                case "blank" -> revision.put("quote", " \t\n ");
                default -> {
                    /* field omitted */
                }
            }
            String eventId = captureIdea(transport, revision).path("eventId").asText();
            JsonNode stored = get("/api/events/" + eventId);
            assertThat(stored.path("metadata").has("quote")).isFalse();
            assertThat(stored.path("text").asText()).doesNotContain("Quote:");
            assertIdeaQuoteViews(key, eventId, quote, ++revisions);
        }
        String replacementQuote = "  a new quoted line 🐈\n";
        var replacement = ideaArguments(key);
        replacement.put("quote", replacementQuote);
        String replacementId =
                captureIdea(transport, replacement).path("eventId").asText();
        assertIdeaQuote(key, replacementId, replacementQuote, ++revisions);
        assertThat(get("/api/events/" + firstId)).isEqualTo(original);
    }

    @ParameterizedTest
    @ValueSource(strings = {"http", "mcp"})
    void ideaQuotePreservationRetainsRedactionAndSeparateCaptureLimits(String transport) throws Exception {
        initialize();
        String key = UUID.randomUUID().toString();
        var args = ideaArguments(key);
        args.put("quote", "    password=\"synthetic-quote-secret\"\n    safe tail  \n");
        String id = captureIdea(transport, args).path("eventId").asText();
        JsonNode redacted = get("/api/events/" + id);
        String safeQuote = redacted.path("metadata").path("quote").asText();
        assertThat(safeQuote)
                .startsWith("    ")
                .endsWith("\n    safe tail  \n")
                .contains("[REDACTED]")
                .doesNotContain("synthetic-quote-secret");
        assertThat(redacted.path("text").asText())
                .contains("Quote: \"" + safeQuote + "\"")
                .doesNotContain("synthetic-quote-secret");
        assertIdeaQuoteViews(key, id, safeQuote, 1);

        for (int length : new int[] {21_000, 51_000}) {
            String largeKey = UUID.randomUUID().toString();
            String quote = "    " + "x".repeat(length) + "\n";
            var large = ideaArguments(largeKey);
            large.put("quote", quote);
            String largeId = captureIdea(transport, large).path("eventId").asText();
            JsonNode stored = get("/api/events/" + largeId);
            String expectedQuote = length < 50_000 ? quote : quote.substring(0, 50_000) + " …[truncated]";
            assertThat(stored.path("metadata").path("quote").asText()).isEqualTo(expectedQuote);
            assertThat(stored.path("text").asText())
                    .contains("Quote: \"    ")
                    .hasSize(20_000 + "\n[truncated]".length())
                    .endsWith("\n[truncated]");
            assertIdeaQuoteViews(largeKey, largeId, expectedQuote, 1);
            assertThat(get("/api/events/" + largeId)).isEqualTo(stored);
        }
        assertThat(get("/api/events/" + id)).isEqualTo(redacted);
    }

    private Map<String, Object> ideaArguments(String key) {

        return new LinkedHashMap<>(Map.of(
                "source",
                "manual",
                "clientSessionId",
                "idea-quote-" + UUID.randomUUID(),
                "repo",
                "/fixture/idea-quote-" + key,
                "title",
                "Quoted code idea",
                "oneLiner",
                "Keep this quoted code",
                "origin",
                "human-aside",
                "ideaKey",
                key));
    }

    private JsonNode captureIdea(String transport, Map<String, Object> args) throws Exception {
        if (transport.equals("mcp"))

            return textJson(call("captureIdea", args));

        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/ideas"))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(args)))
                .build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);

        return mapper.readTree(response.body());
    }

    private JsonNode assertIdeaQuote(String key, String id, String quote, int revisions) throws Exception {
        JsonNode event = get("/api/events/" + id);
        assertThat(event.path("metadata").path("quote").asText()).isEqualTo(quote);
        assertThat(event.path("text").asText()).contains("Quote: \"" + quote + "\"");
        assertIdeaQuoteViews(key, id, quote, revisions);

        return event;
    }

    private void assertIdeaQuoteViews(String key, String id, String quote, int revisions) throws Exception {
        JsonNode listing = get("/api/ideas?q=" + key).path("items");
        assertThat(listing).hasSize(1);
        for (JsonNode idea : List.of(
                listing.get(0),
                get("/api/ideas/detail?ideaKey=" + key).path("idea"),
                textJson(call("recallIdea", Map.of("ideaKey", key))).path("idea"))) {
            assertThat(idea.path("quote").asText()).isEqualTo(quote);
            assertThat(idea.path("eventId").asText()).isEqualTo(id);
            assertThat(idea.path("revisions").asInt()).isEqualTo(revisions);
        }
    }

    private Map<String, Object> projectionArguments(String firstDescription) {
        Map<String, Object> args = validArguments("captureProjection");
        args.put("basis", "Recovery must be proven before migration");
        args.put(
                "paths",
                List.of(
                        Map.of("title", "Ship migration", "description", firstDescription, "confidence", 0.6),
                        Map.of(
                                "title",
                                "Defer migration",
                                "description",
                                "Keep current storage if recovery checks fail",
                                "confidence",
                                0.4)));

        return args;
    }

    @Test
    void mcpResponseParserRetainsNanosecondTimestampPrecision() throws Exception {
        Instant expected = Instant.parse("2026-10-03T04:12:34.123456789Z");
        JsonNode result = mapper.valueToTree(Map.of(
                "content", List.of(Map.of("text", "{\"observedAt\":" + expected.getEpochSecond() + ".123456789}"))));
        assertThat(mapper.convertValue(textJson(result).path("observedAt"), Instant.class))
                .isEqualTo(expected);
    }

    private void assertProjectionIdentity(JsonNode item, JsonNode canonical) {
        for (String field : List.of("sessionId", "source", "clientSessionId")) {
            assertThat(item.path(field)).as(field).isEqualTo(canonical.path(field));
        }
        // MCP's established serializer uses epoch seconds; REST uses ISO text.
        assertThat(mapper.convertValue(item.path("observedAt"), Instant.class))
                .isEqualTo(mapper.convertValue(canonical.path("observedAt"), Instant.class));
        assertThat(item.path("eventId").asText()).isEqualTo(canonical.path("id").asText());
        assertThat(item.path("kind").asText()).isEqualTo("projection");
        assertThat(item.path("repo")).isEqualTo(canonical.path("metadata").path("repo"));
    }

    @Test
    void projectRecallAndExplicitReplacementWorkThroughRealMcpTransport() throws Exception {
        initialize();
        Map<String, Object> args = validArguments("captureDecision");
        args.put("clientSessionId", "mcp-continuity-original");
        args.put("repo", "/fixture/mcp-continuity");
        args.put("decision", "Original continuity decision");
        String original =
                textJson(call("captureDecision", args)).path("eventId").asText();
        args.put("clientSessionId", "mcp-continuity-other");
        args.put("repo", "/fixture/mcp-other");
        textJson(call("captureDecision", args));
        var selected =
                textJson(call("recallContext", Map.of("project", "/fixture/mcp-continuity", "query", "continuity")));
        assertThat(selected.path("items")).hasSize(1);
        assertThat(selected.path("items").get(0).path("eventId").asText()).isEqualTo(original);
        args.put("repo", "/fixture/mcp-continuity");
        args.put("clientSessionId", "mcp-continuity-replacement");
        args.put("decision", "Replacement continuity decision");
        args.put("rationale", "New evidence overturned the original choice");
        args.put("supersedes", original);
        String replacement =
                textJson(call("captureDecision", args)).path("eventId").asText();
        var current =
                textJson(call("recallContext", Map.of("project", "/fixture/mcp-continuity", "query", "continuity")));
        assertThat(current.path("items")).hasSize(1);
        assertThat(current.path("items").get(0).path("supersedesEventId").asText())
                .isEqualTo(original);
        var history = textJson(call("recallContext", Map.of("repoOrTopic", original, "includeSuperseded", true)));
        assertThat(history.path("items").get(0).path("supersededByEventId").asText())
                .isEqualTo(replacement);
        long before = eventCount();
        assertThat(call("captureDecision", args).path("isError").asBoolean()).isTrue();
        assertThat(eventCount()).isEqualTo(before);
        assertThat(call("recallContext", Map.of("repoOrTopic", "legacy", "project", "/fixture/mcp-continuity"))
                        .path("isError")
                        .asBoolean())
                .isTrue();
    }

    private void assertHandoff(JsonNode item, String eventId) {
        assertThat(item.path("eventId").asText()).isEqualTo(eventId);
        assertThat(item.path("source").asText()).isEqualTo("manual");
        assertThat(item.path("headline").asText()).isEqualTo("Verified the synthetic checkpoint");
        assertThat(item.has("body")).isFalse();
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
            case "captureHandoff" ->
                args.putAll(Map.of(
                        "contextSummary",
                        "Verified the synthetic checkpoint",
                        "toAgent",
                        "next-session",
                        "openLoops",
                        List.of("User review remains"),
                        "nextAction",
                        "Inspect the saved checkpoint"));
            case "captureDecision" -> args.put("decision", "Keep the fixture isolated");
            case "captureObservation" -> args.put("text", "Synthetic observation");
            case "captureProjection" -> args.put("paths", List.of(Map.of("title", "Review the fixture")));
            default -> throw new IllegalArgumentException(tool);
        }

        return args;
    }

    private void initialize() throws Exception {
        rpc(
                "initialize",
                Map.of(
                        "protocolVersion",
                        "2024-11-05",
                        "capabilities",
                        Map.of(),
                        "clientInfo",
                        Map.of("name", "capture-contract", "version", "1")));
        post(Map.of("jsonrpc", "2.0", "method", "notifications/initialized"));
    }

    private JsonNode call(String name, Map<String, Object> args) throws Exception {

        return rpc("tools/call", Map.of("name", name, "arguments", args));
    }

    private JsonNode rpc(String method, Map<String, Object> params) throws Exception {
        HttpResponse<String> response =
                post(Map.of("jsonrpc", "2.0", "id", ++requestId, "method", method, "params", params));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        String body = response.body();
        if (response.headers().firstValue("Content-Type").orElse("").contains("text/event-stream")) {
            body = body.lines()
                    .filter(line -> line.startsWith("data:"))
                    .map(line -> line.substring(5).strip())
                    .findFirst()
                    .orElseThrow();
        }
        JsonNode envelope = mapper.readTree(body);
        assertThat(envelope.has("error")).as(envelope.toString()).isFalse();

        return envelope.path("result");
    }

    private HttpResponse<String> post(Map<String, Object> body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp"))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream");
        if (session != null) request.header("Mcp-Session-Id", session);
        HttpResponse<String> response = client.send(
                request.POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        response.headers().firstValue("Mcp-Session-Id").ifPresent(value -> session = value);

        return response;
    }

    private JsonNode textJson(JsonNode result) throws Exception {
        assertThat(result.path("isError").asBoolean()).as(result.toString()).isFalse();

        return mapper.reader()
                .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .readTree(result.path("content").get(0).path("text").asText());
    }

    private long eventCount() throws Exception {
        JsonNode count = get("/api/status").path("storage").path("events");
        assertThat(count.isIntegralNumber())
                .as("status must provide a real event count")
                .isTrue();

        return count.asLong();
    }

    private JsonNode get(String path) throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                        .timeout(Duration.ofSeconds(15))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);

        return mapper.readTree(response.body());
    }
}
