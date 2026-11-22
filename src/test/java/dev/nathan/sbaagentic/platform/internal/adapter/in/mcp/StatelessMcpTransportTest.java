package dev.nathan.sbaagentic.platform.internal.adapter.in.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nathan.sbaagentic.SbaAgenticApplication;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;

/**
 * MCP runs stateless so a restart cannot strand a client. Under the stateful transport every
 * restart dropped the in-memory {@code Mcp-Session-Id} table, and a client that reconnected then
 * opened {@code GET /mcp}, which sent no bytes, so it waited on the headers forever.
 */
@Timeout(value = 2, unit = TimeUnit.MINUTES)
class StatelessMcpTransportTest {

    /** What the MCP TypeScript, Rust and Go SDKs (Claude Code, Codex, Antigravity) send. */
    private static final String CLIENT_ACCEPT = "application/json, text/event-stream";

    @TempDir
    Path directory;

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();
    private final List<ServletWebServerApplicationContext> started = new ArrayList<>();

    @AfterEach
    void closeEverythingStarted() {
        started.forEach(ServletWebServerApplicationContext::close);
        http.shutdownNow();
    }

    @Test
    void listeningStreamIsRefusedPromptlyInsteadOfHanging() throws Exception {
        var app = start();

        var response = http.send(
                HttpRequest.newBuilder(URI.create(base(app) + "/mcp"))
                        .timeout(Duration.ofSeconds(5))
                        .header("Accept", "text/event-stream")
                        .header("Mcp-Session-Id", "left-over-from-a-previous-process")
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(405);
    }

    @Test
    void toolCallsWorkWithoutASessionAndAcrossARestart() throws Exception {
        var before = start();

        var initialize = post(before, CLIENT_ACCEPT, initializeRequest());
        assertThat(initialize.statusCode()).as(initialize.body()).isEqualTo(200);
        assertThat(initialize.headers().firstValue("Mcp-Session-Id")).isEmpty();
        assertThat(json.readTree(initialize.body())
                        .path("result")
                        .path("serverInfo")
                        .path("name")
                        .asText())
                .isEqualTo("black-box");

        // No initialize, no notifications/initialized, no session: each POST stands alone.
        assertThat(toolNames(before)).contains("setBroadcast", "listBroadcasts", "recallContext");
        var set = call(
                before,
                "setBroadcast",
                Map.of(
                        "source", "codex",
                        "clientSessionId", "stateless-before",
                        "repo", "/repos/stateless-fixture",
                        "key", "stateless-restart",
                        "message", "Set before the restart",
                        "kind", "invariant"));
        assertThat(set.path("isError").asBoolean()).as(set.toString()).isFalse();

        before.close();
        var after = start();

        // A client that kept running across the restart just sends its next call.
        var listed = call(after, "listBroadcasts", Map.of("project", "/repos/stateless-fixture"));
        assertThat(listed.path("isError").asBoolean()).as(listed.toString()).isFalse();
        assertThat(listed.path("content").path(0).path("text").asText())
                .contains("stateless-restart")
                .contains("Set before the restart");

        var recalled = call(after, "recallContext", Map.of("repoOrTopic", "/repos/stateless-fixture"));
        assertThat(recalled.path("isError").asBoolean()).as(recalled.toString()).isFalse();

        var recent = call(after, "recentSessions", Map.of());
        assertThat(recent.path("isError").asBoolean()).as(recent.toString()).isFalse();
    }

    @Test
    void postsMustAcceptBothJsonAndEventStreamAsTheSpecRequires() throws Exception {
        var app = start();
        Map<String, Object> list = rpc(1, "tools/list", Map.of());

        assertThat(post(app, CLIENT_ACCEPT, list).statusCode()).isEqualTo(200);
        assertThat(post(app, "text/event-stream, application/json", list).statusCode())
                .isEqualTo(200);
        assertThat(post(app, "application/json", list).statusCode()).isEqualTo(400);
        assertThat(post(app, "*/*", list).statusCode()).isEqualTo(400);
    }

    private ServletWebServerApplicationContext start() {
        var app = (ServletWebServerApplicationContext) new SpringApplicationBuilder(SbaAgenticApplication.class)
                .run(
                        "--spring.config.location=classpath:/application.yml",
                        "--spring.profiles.active=default",
                        "--spring.datasource.url=jdbc:sqlite:" + directory.resolve("stateless.db"),
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
                        "--sba.exports.targets[0].enabled=false",
                        "--spring.main.banner-mode=off",
                        "--logging.level.root=WARN");
        started.add(app);

        return app;
    }

    private static String base(ServletWebServerApplicationContext app) {

        return "http://127.0.0.1:" + app.getWebServer().getPort();
    }

    private List<String> toolNames(ServletWebServerApplicationContext app) throws Exception {
        var response = post(app, CLIENT_ACCEPT, rpc(2, "tools/list", Map.of()));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);

        List<String> names = new ArrayList<>();
        json.readTree(response.body())
                .path("result")
                .path("tools")
                .forEach(tool -> names.add(tool.path("name").asText()));

        return names;
    }

    private JsonNode call(ServletWebServerApplicationContext app, String tool, Map<String, Object> arguments)
            throws Exception {
        var response = post(app, CLIENT_ACCEPT, rpc(3, "tools/call", Map.of("name", tool, "arguments", arguments)));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);

        JsonNode body = json.readTree(response.body());
        assertThat(body.has("error")).as(response.body()).isFalse();

        return body.path("result");
    }

    private HttpResponse<String> post(
            ServletWebServerApplicationContext app, String accept, Map<String, Object> message) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base(app) + "/mcp"))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .header("Accept", accept)
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(message)))
                .build();

        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static Map<String, Object> initializeRequest() {

        return rpc(
                0,
                "initialize",
                Map.of(
                        "protocolVersion", "2025-11-25",
                        "capabilities", Map.of(),
                        "clientInfo", Map.of("name", "stateless-fixture", "version", "1")));
    }

    private static Map<String, Object> rpc(int id, String method, Map<String, Object> params) {

        return Map.of("jsonrpc", "2.0", "id", id, "method", method, "params", params);
    }
}
