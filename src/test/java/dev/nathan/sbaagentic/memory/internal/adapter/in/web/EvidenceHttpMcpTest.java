package dev.nathan.sbaagentic.memory.internal.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nathan.sbaagentic.SbaAgenticApplication;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

/** Actual HTTP and MCP against an isolated canonical store; no model or transcript provider. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EvidenceHttpMcpTest {
    @TempDir
    static Path temp;

    private final HttpClient client = HttpClient.newHttpClient();
    private ServletWebServerApplicationContext app;
    private ObjectMapper mapper;
    private JdbcTemplate jdbc;
    private String base;
    private String session;
    private int requestId;

    @BeforeAll
    void start() throws Exception {
        Map<String, Object> database = databaseProperties();
        app = (ServletWebServerApplicationContext) new SpringApplicationBuilder(SbaAgenticApplication.class)
                .profiles(databaseProfiles())
                .initializers(context -> context.getEnvironment()
                        .getPropertySources()
                        .addFirst(new org.springframework.core.env.MapPropertySource(
                                "evidence-fixture-database", database)))
                .run(
                        "--spring.config.location=classpath:/application.yml",
                        "--server.address=127.0.0.1",
                        "--server.port=0",
                        "--sba.auth.enabled=false",
                        "--sba.editor.enabled=false",
                        "--sba.local-ai.enabled=false",
                        "--sba.summary.backend=local",
                        "--sba.elasticsearch.enabled=false",
                        "--sba.memory.embedding.enabled=false",
                        "--sba.ask.embedding-enabled=false",
                        "--sba.judge.enabled=false",
                        "--sba.transcript.claude-roots[0]=" + temp.resolve("unavailable"),
                        "--sba.transcript.codex-roots[0]=" + temp.resolve("unavailable"),
                        "--sba.exports.targets[0].enabled=false",
                        "--spring.main.banner-mode=off",
                        "--logging.level.root=WARN");
        mapper = app.getBean(ObjectMapper.class);
        jdbc = app.getBean(JdbcTemplate.class);
        base = "http://127.0.0.1:" + app.getWebServer().getPort();
        rpc(
                "initialize",
                Map.of(
                        "protocolVersion",
                        "2024-11-05",
                        "capabilities",
                        Map.of(),
                        "clientInfo",
                        Map.of("name", "evidence-fixture", "version", "1")));
    }

    protected Map<String, Object> databaseProperties() throws Exception {

        return Map.of("spring.datasource.url", "jdbc:sqlite:" + temp.resolve("evidence.db"));
    }

    protected String[] databaseProfiles() {

        return new String[0];
    }

    protected void cleanupDatabase() throws Exception {}

    @AfterAll
    void stop() throws Exception {
        try {
            if (app != null) app.close();
            client.close();
        } finally {
            cleanupDatabase();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"http", "mcp"})
    void capturesAndJoinsAllIdeaRevisionsWithCanonicalProvenance(String transport) throws Exception {
        String key = UUID.randomUUID().toString(), canonical = "/fixture/" + key, alias = canonical + "-alias";
        http("PUT", "/api/project-aliases", Map.of("aliasKey", alias, "canonicalKey", canonical));
        Map<String, Object> idea = baseArgs();
        idea.putAll(Map.of(
                "repo",
                alias,
                "title",
                "Verified idea",
                "oneLiner",
                "Keep the fact",
                "origin",
                "joint",
                "ideaKey",
                key,
                "project",
                "Home",
                "alsoIn",
                List.of(Map.of("project", "Other", "score", 1))));
        JsonNode first = success(capture(transport, "Idea", idea), transport);
        String originalId = first.path("eventId").asText();
        Map<String, Object> original = row(originalId);
        Map<String, Object> revision = baseArgs();
        revision.putAll(Map.of(
                "title",
                "Verified idea",
                "oneLiner",
                "Keep the fact",
                "origin",
                "joint",
                "ideaKey",
                key,
                "status",
                "tracked"));
        JsonNode latest = success(capture(transport, "Idea", revision), transport);
        Map<String, Object> fact = evidence();
        fact.putAll(Map.of(
                "repo",
                alias,
                "claim",
                "Verified fact " + key,
                "sourceRef",
                "fixture.txt:12",
                "excerpt",
                "    Measured result\n",
                "observedAt",
                "2001-01-01T01:00:00+01:00",
                "capturedBy",
                "fixture reviewer",
                "supports",
                List.of("IDEA: " + key, "idea:" + key),
                "notes",
                "Verify before reuse"));
        JsonNode captured = success(capture(transport, "Evidence", fact), transport);
        String factId = captured.path("eventId").asText();
        Map<String, Object> saved = row(factId);
        Map<String, Object> refuting = evidence();
        refuting.putAll(
                Map.of("repo", alias, "claim", "Old revision failed", "refutes", List.of(originalId.substring(0, 8))));
        success(capture(transport, "Evidence", refuting), transport);
        JsonNode detail = transport.equals("mcp")
                ? tool("recallIdea", Map.of("ideaKey", key))
                : http("GET", "/api/ideas/detail?ideaKey=" + key, null).body();
        assertThat(detail.path("idea").path("eventId")).isEqualTo(latest.path("eventId"));
        assertThat(detail.path("idea").path("repo").asText()).isEqualTo(alias);
        assertThat(detail.path("idea").path("project").asText()).isEqualTo("Home");
        assertThat(detail.path("idea").path("alsoIn")).hasSize(1);
        assertThat(detail.path("supports")).hasSize(1);
        assertThat(detail.path("refutes")).hasSize(1);
        JsonNode proof = detail.path("supports").get(0);
        assertThat(proof.path("sourceRef").asText()).isEqualTo("fixture.txt:12");
        assertThat(proof.path("excerpt").asText()).isEqualTo("    Measured result\n");
        assertThat(mapper.treeToValue(proof.path("observedAt"), java.time.Instant.class))
                .isEqualTo(java.time.Instant.parse("2001-01-01T00:00:00Z"));
        assertThat(proof.path("capturedAt").asText())
                .isNotEqualTo(proof.path("observedAt").asText());
        assertThat(proof.path("capturedBy").asText()).isEqualTo("fixture reviewer");
        assertThat(proof.path("supports")).hasSize(1);
        for (String scope : List.of(canonical, alias)) {
            JsonNode listed = http(
                            "GET", "/api/evidence?project=" + encode(scope) + "&q=" + encode("Verified fact"), null)
                    .body();
            assertThat(listed.path("items")).hasSize(1);
            assertThat(listed.path("items").get(0).path("eventId").asText()).isEqualTo(factId);
            JsonNode recalled = http(
                            "GET",
                            "/api/recall?project=" + encode(scope) + "&query=" + encode("Verified fact")
                                    + "&kinds=evidence",
                            null)
                    .body();
            assertThat(recalled.path("items")).hasSize(1);
            assertThat(recalled.path("items").get(0).path("body").asText()).isEqualTo(saved.get("text"));
            assertThat(recalled.path("items").get(0).path("body").asText())
                    .contains("Excerpt:     Measured result\n", "Source: fixture.txt:12", "Verify before reuse");
        }
        assertThat(http("GET", "/api/recall?project=" + encode(canonical) + "&query=" + key, null)
                        .body()
                        .path("items"))
                .isEmpty();
        assertThat(http("GET", "/api/evidence?project=" + encode(canonical + "-other") + "&q=" + key, null)
                        .body()
                        .path("items"))
                .isEmpty();
        assertThat(row(originalId)).isEqualTo(original);
        assertThat(row(factId)).isEqualTo(saved);
    }

    static Stream<Arguments> invalidInputs() {
        var cases = List.of(
                Map.entry("source", Map.<String, Object>of("source", " ")),
                Map.entry("clientSessionId", Map.<String, Object>of("clientSessionId", " ")),
                Map.entry("claim", Map.<String, Object>of("claim", " ")),
                Map.entry("sourceRef", Map.<String, Object>of("sourceRef", " ")),
                Map.entry("observedAt", Map.<String, Object>of("observedAt", "yesterday")),
                Map.entry("outputDigest", Map.<String, Object>of("outputDigest", "x".repeat(201))),
                Map.entry("supports", Map.<String, Object>of("supports", List.of("untyped link"))),
                Map.entry(
                        "supports and refutes",
                        Map.<String, Object>of("supports", List.of("idea:key"), "refutes", List.of("IDEA:key"))),
                Map.entry(
                        "alsoIn project",
                        Map.<String, Object>of("alsoIn", List.of(Map.of("project", " ", "score", 0.5)))),
                Map.entry(
                        "alsoIn score",
                        Map.<String, Object>of("alsoIn", List.of(Map.of("project", "Other", "score", "NaN")))),
                Map.entry(
                        "alsoIn score",
                        Map.<String, Object>of("alsoIn", List.of(Map.of("project", "Other", "score", 1.1)))),
                Map.entry(
                        "home lane",
                        Map.<String, Object>of(
                                "project", "Home", "alsoIn", List.of(Map.of("project", " home ", "score", 0)))));

        return Stream.of("http", "mcp")
                .flatMap(t -> cases.stream().map(c -> Arguments.of(t, c.getKey(), c.getValue())));
    }

    @ParameterizedTest
    @MethodSource("invalidInputs")
    void invalidEvidenceWritesNeitherEventNorSession(String transport, String field, Map<String, Object> override)
            throws Exception {
        long events = count("agent_events"), sessions = count("agent_sessions");
        Map<String, Object> args = evidence();
        args.putAll(override);
        Response response = capture(transport, "Evidence", args);
        if (transport.equals("http")) assertThat(response.status()).isEqualTo(400);
        else assertThat(response.body().path("isError").asBoolean()).isTrue();
        assertThat(response.body().toString()).contains(field).doesNotContain("java.lang.", "StructuredCaptureService");
        assertThat(count("agent_events")).isEqualTo(events);
        assertThat(count("agent_sessions")).isEqualTo(sessions);
    }

    @Test
    void mcpClipsEvidenceBodyWithoutLosingItsCanonicalAnchor() throws Exception {
        Map<String, Object> args = evidence();
        String repo = "/fixture/clamp/" + UUID.randomUUID();
        args.putAll(Map.of("repo", repo, "excerpt", "🧪".repeat(5000)));
        String id = tool("captureEvidence", args).path("eventId").asText();
        Map<String, Object> stored = row(id);
        JsonNode recalled =
                tool("recallContext", Map.of("project", repo, "kinds", List.of("evidence"), "maxChars", 700));
        assertThat(recalled.path("truncated").asBoolean()).isTrue();
        assertThat(recalled.path("items")).hasSize(1);
        JsonNode item = recalled.path("items").get(0);
        assertThat(item.path("eventId").asText()).isEqualTo(id);
        assertThat(item.path("sessionId").asText()).isEqualTo(stored.get("session_id"));
        assertThat(mapper.treeToValue(item.path("observedAt"), java.time.Instant.class))
                .isEqualTo(java.time.Instant.parse(stored.get("observed_at").toString()));
        assertThat(item.path("body").asText()).contains("chars)");
        assertThat(row(id)).isEqualTo(stored);
    }

    @Test
    void captureTextLimitAndRecallLimitHaveDistinctProvenance() throws Exception {
        Map<String, Object> args = evidence();
        String repo = "/fixture/large/" + UUID.randomUUID();
        String notes = "Canonical notes. ".repeat(1600);
        args.putAll(Map.of("repo", repo, "notes", notes));
        String id = tool("captureEvidence", args).path("eventId").asText();
        Map<String, Object> stored = row(id);
        String text = stored.get("text").toString();
        assertThat(text).endsWith("[truncated]");
        assertThat(mapper.readTree(stored.get("metadata_json").toString())
                        .path("notes")
                        .asText())
                .isEqualTo(notes.strip());
        JsonNode rest = http("GET", "/api/recall?project=" + encode(repo) + "&kinds=evidence", null)
                .body();
        assertThat(rest.path("items").get(0).path("body").asText()).isEqualTo(text);
        assertThat(rest.path("truncated").asBoolean()).isFalse();
        JsonNode bounded =
                tool("recallContext", Map.of("project", repo, "kinds", List.of("evidence"), "maxChars", 700));
        assertThat(bounded.path("truncated").asBoolean()).isTrue();
        assertThat(bounded.path("items").get(0).path("eventId").asText()).isEqualTo(id);
        assertThat(row(id)).isEqualTo(stored);
    }

    private Map<String, Object> baseArgs() {

        return new LinkedHashMap<>(
                Map.of("source", "manual", "clientSessionId", UUID.randomUUID().toString()));
    }

    private Map<String, Object> evidence() {
        var args = baseArgs();
        args.putAll(Map.of("claim", "Fixture fact", "sourceRef", "fixture run"));

        return args;
    }

    private Map<String, Object> row(String id) {

        return jdbc.queryForMap("SELECT * FROM agent_events WHERE id=?", id);
    }

    private long count(String table) {

        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    private static String encode(String text) {

        return URLEncoder.encode(text, StandardCharsets.UTF_8);
    }

    private Response capture(String transport, String kind, Map<String, Object> args) throws Exception {

        return transport.equals("mcp")
                ? rpc("tools/call", Map.of("name", "capture" + kind, "arguments", args))
                : http("POST", kind.equals("Idea") ? "/api/ideas" : "/api/evidence", args);
    }

    private JsonNode tool(String name, Map<String, Object> args) throws Exception {

        return success(rpc("tools/call", Map.of("name", name, "arguments", args)), "mcp");
    }

    private JsonNode success(Response response, String transport) throws Exception {
        assertThat(response.status()).isEqualTo(200);
        if (transport.equals("http"))

            return response.body();

        assertThat(response.body().path("isError").asBoolean())
                .as(response.body().toString())
                .isFalse();

        return mapper.reader()
                .with(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .readTree(response.body().path("content").get(0).path("text").asText());
    }

    private Response rpc(String method, Map<String, Object> params) throws Exception {
        Response response =
                http("POST", "/mcp", Map.of("jsonrpc", "2.0", "id", ++requestId, "method", method, "params", params));
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body().has("error")).isFalse();

        return new Response(response.status(), response.body().path("result"));
    }

    private Response http(String method, String path, Object body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream");
        if (path.equals("/mcp") && session != null) request.header("Mcp-Session-Id", session);
        var response = client.send(
                request.method(
                                method,
                                body == null
                                        ? HttpRequest.BodyPublishers.noBody()
                                        : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        if (path.equals("/mcp")) response.headers().firstValue("Mcp-Session-Id").ifPresent(s -> session = s);
        String content = response.body();
        if (response.headers().firstValue("Content-Type").orElse("").contains("text/event-stream"))
            content = content.lines()
                    .filter(l -> l.startsWith("data:"))
                    .map(l -> l.substring(5).strip())
                    .findFirst()
                    .orElseThrow();

        return new Response(response.statusCode(), mapper.readTree(content));
    }

    private record Response(int status, JsonNode body) {}
}
