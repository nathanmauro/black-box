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
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

/** Real REST/MCP captures and listing against disposable SQLite; no external systems. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IdeaProjectInheritanceHttpTest {
    private static final String CANONICAL = "/fixture/idea-project";
    private static final String ALIAS = "/fixture/idea-worktree";

    @TempDir
    static Path tempDir;

    private final HttpClient client = HttpClient.newHttpClient();
    private ServletWebServerApplicationContext app;
    private ObjectMapper mapper;
    private JdbcTemplate jdbc;
    private String base;
    private String mcpSession;
    private int requestId;

    @BeforeAll
    void start() throws Exception {
        app = (ServletWebServerApplicationContext) new SpringApplicationBuilder(SbaAgenticApplication.class)
                .run(
                        "--spring.config.location=classpath:/application.yml",
                        "--spring.datasource.url=jdbc:sqlite:" + tempDir.resolve("ideas.db"),
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
                        Map.of("name", "idea-project-fixture", "version", "1")));
        http("PUT", "/api/project-aliases", Map.of("aliasKey", ALIAS, "canonicalKey", CANONICAL));
    }

    @AfterAll
    void stop() {
        if (app != null) app.close();
        client.close();
    }

    static Stream<Arguments> captures() {

        return Stream.of("http", "mcp")
                .flatMap(transport -> Stream.of(CANONICAL, ALIAS).map(repo -> Arguments.of(transport, repo)));
    }

    @ParameterizedTest(name = "{0} recapture inherits {1}")
    @MethodSource("captures")
    void missingRepoKeepsLatestRevisionInCanonicalAndAliasFilters(String transport, String originalRepo)
            throws Exception {
        String key = UUID.randomUUID().toString();
        var firstArgs = arguments(key, originalRepo, "untouched");
        firstArgs.put("quote", "Keep this durable evidence");
        firstArgs.put("notes", "Prior optional detail");
        JsonNode first = capture(transport, firstArgs);
        String firstId = first.path("eventId").asText();
        Map<String, Object> original = row(firstId);
        JsonNode latest = capture(transport, arguments(key, null, "tracked"));
        String latestId = latest.path("eventId").asText();
        Map<String, Object> latestRow = row(latestId);
        assertThat(mapper.readTree(latestRow.get("metadata_json").toString()).has("repo"))
                .isFalse();
        assertThat(jdbc.queryForObject(
                        "SELECT cwd FROM agent_sessions WHERE id=?",
                        String.class,
                        latest.path("sessionId").asText()))
                .isNull();

        for (String scope : new String[] {CANONICAL, CANONICAL + "/", ALIAS}) {
            JsonNode response =
                    list("project=" + encode(scope) + "&q=" + key + "&status=tracked&origin=agent-proposed");
            assertThat(response.path("count").asInt()).isEqualTo(1);
            JsonNode idea = response.path("items").get(0);
            assertThat(idea.path("repo").asText()).isEqualTo(originalRepo);
            assertThat(idea.path("eventId")).isEqualTo(latest.path("eventId"));
            assertThat(idea.path("sessionId")).isEqualTo(latest.path("sessionId"));
            assertThat(idea.path("clientSessionId")).isEqualTo(latest.path("clientSessionId"));
            assertThat(idea.path("source")).isEqualTo(latest.path("source"));
            assertThat(idea.path("status").asText()).isEqualTo("tracked");
            assertThat(idea.path("revisions").asInt()).isEqualTo(2);
            assertThat(idea.path("quote").asText()).isEqualTo("Keep this durable evidence");
            assertThat(idea.path("notes").asText()).isEqualTo("Prior optional detail");
            assertThat(idea.path("capturedAt").asText()).isEqualTo(latestRow.get("observed_at"));
            assertThat(idea.path("firstCapturedAt").asText()).isEqualTo(original.get("observed_at"));
        }
        assertThat(list("repo=" + encode(ALIAS) + "&q=" + key).path("count").asInt())
                .isEqualTo(1);
        assertThat(list("project=" + encode(CANONICAL + "-other") + "&q=" + key)
                        .path("count")
                        .asInt())
                .isZero();
        assertThat(list("status=untouched&q=" + key).path("count").asInt()).isZero();
        assertThat(row(firstId)).isEqualTo(original);
        assertThat(row(latestId)).isEqualTo(latestRow);
    }

    @Test
    void newerExplicitRepoStillMovesListingToItsNewProject() throws Exception {
        String key = UUID.randomUUID().toString();
        JsonNode first = capture("http", arguments(key, CANONICAL, "untouched"));
        Map<String, Object> original = row(first.path("eventId").asText());
        String moved = "/fixture/idea-moved";
        JsonNode latest = capture("mcp", arguments(key, moved, "tracked"));
        assertThat(list("project=" + encode(CANONICAL) + "&q=" + key)
                        .path("count")
                        .asInt())
                .isZero();
        JsonNode listed = list("project=" + encode(moved) + "&q=" + key).path("items");
        assertThat(listed).hasSize(1);
        assertThat(listed.get(0).path("eventId")).isEqualTo(latest.path("eventId"));
        assertThat(listed.get(0).path("repo").asText()).isEqualTo(moved);
        assertThat(row(first.path("eventId").asText())).isEqualTo(original);
    }

    @Test
    void newerCwdOnlyCaptureStillWinsOverOlderCapturedRepo() throws Exception {
        String key = UUID.randomUUID().toString();
        JsonNode first = capture("http", arguments(key, CANONICAL, "untouched"));
        Map<String, Object> original = row(first.path("eventId").asText());
        String cwd = "/fixture/legacy-newer";
        var envelope = Map.of(
                "captureId",
                UUID.randomUUID().toString(),
                "event",
                Map.of(
                        "source",
                        "manual",
                        "clientSessionId",
                        "legacy-" + UUID.randomUUID(),
                        "eventType",
                        "Idea",
                        "text",
                        "[Idea] Fixture",
                        "cwd",
                        cwd,
                        "metadata",
                        Map.of("kind", "idea", "ideaKey", key, "title", "Fixture", "status", "tracked")));
        JsonNode latest = http("POST", "/api/events/idempotent", envelope);
        Map<String, Object> latestRow = row(latest.path("eventId").asText());
        assertThat(mapper.readTree(latestRow.get("metadata_json").toString()).has("repo"))
                .isFalse();
        JsonNode listed = list("project=" + encode(cwd) + "&q=" + key).path("items");
        assertThat(listed).hasSize(1);
        assertThat(listed.get(0).path("repo").asText()).isEqualTo(cwd);
        assertThat(listed.get(0).path("eventId")).isEqualTo(latest.path("eventId"));
        assertThat(list("project=" + encode(CANONICAL) + "&q=" + key)
                        .path("count")
                        .asInt())
                .isZero();
        JsonNode replay = http("POST", "/api/events/idempotent", envelope);
        assertThat(replay.path("replayed").asBoolean()).isTrue();
        assertThat(row(first.path("eventId").asText())).isEqualTo(original);
        assertThat(row(latest.path("eventId").asText())).isEqualTo(latestRow);
    }

    private Map<String, Object> arguments(String key, String repo, String status) {
        var args = new LinkedHashMap<String, Object>(Map.of(
                "source",
                "manual",
                "clientSessionId",
                "idea-project-" + UUID.randomUUID(),
                "title",
                "Fixture idea",
                "oneLiner",
                "Bounded continuation",
                "origin",
                "agent-proposed",
                "ideaKey",
                key,
                "status",
                status));
        if (repo != null) args.put("repo", repo);

        return args;
    }

    private Map<String, Object> row(String id) {

        return jdbc.queryForMap("SELECT * FROM agent_events WHERE id=?", id);
    }

    private JsonNode capture(String transport, Map<String, Object> arguments) throws Exception {
        if (transport.equals("http"))

            return http("POST", "/api/ideas", arguments);

        JsonNode response = rpc("tools/call", Map.of("name", "captureIdea", "arguments", arguments));
        assertThat(response.path("isError").asBoolean()).as(response.toString()).isFalse();

        return mapper.readTree(response.path("content").get(0).path("text").asText());
    }

    private JsonNode list(String query) throws Exception {

        return http("GET", "/api/ideas?" + query, null);
    }

    private String encode(String value) {

        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private JsonNode rpc(String method, Map<String, Object> params) throws Exception {
        JsonNode response =
                http("POST", "/mcp", Map.of("jsonrpc", "2.0", "id", ++requestId, "method", method, "params", params));
        assertThat(response.has("error")).as(response.toString()).isFalse();

        return response.path("result");
    }

    private JsonNode http(String method, String path, Object body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream");
        if (path.equals("/mcp") && mcpSession != null) request.header("Mcp-Session-Id", mcpSession);
        request.method(
                method,
                body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
        var response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        if (path.equals("/mcp")) response.headers().firstValue("Mcp-Session-Id").ifPresent(value -> mcpSession = value);
        String content = response.body();
        if (response.headers().firstValue("Content-Type").orElse("").contains("text/event-stream"))
            content = content.lines()
                    .filter(line -> line.startsWith("data:"))
                    .map(line -> line.substring(5).strip())
                    .findFirst()
                    .orElseThrow();

        return mapper.readTree(content);
    }
}
