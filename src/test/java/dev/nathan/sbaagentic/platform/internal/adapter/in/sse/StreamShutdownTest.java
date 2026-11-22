package dev.nathan.sbaagentic.platform.internal.adapter.in.sse;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nathan.sbaagentic.SbaAgenticApplication;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.catalina.Container;
import org.apache.catalina.core.StandardContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.embedded.tomcat.TomcatWebServer;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;

/**
 * Restart the service while stream clients are attached. Browser tabs and stream followers hold
 * Server-Sent Event responses open indefinitely, and graceful shutdown waits for every open request.
 * MCP is stateless, so it never holds a listening stream that shutdown would have to end. The streams must end when shutdown begins, or a service manager's exit timeout
 * (5 s for the launchd job) kills the process before queued work and post-commit fan-out finish.
 */
@Timeout(value = 2, unit = TimeUnit.MINUTES)
class StreamShutdownTest {

    /**
     * Graceful shutdown gives up on open requests after this phase timeout. It is raised above the
     * shipped value so that only ending the streams, not the shorter shipped backstop, can pass.
     */
    private static final String PHASE_TIMEOUT = "10s";

    /** Leaves headroom inside the 5 s launchd exit timeout for the JVM to exit. */
    private static final Duration PROMPT_SHUTDOWN = Duration.ofSeconds(3);

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
    void shutdownEndsAttachedEventStreamsInsteadOfWaitingForThem() throws Exception {
        var app = start("event-stream.db");
        var stream = http.send(eventStream(app, null), HttpResponse.BodyHandlers.ofInputStream());
        var input = new BufferedReader(new InputStreamReader(stream.body(), StandardCharsets.UTF_8));
        assertThat(input.readLine()).isEqualTo(":connected");
        assertThat(openStreams(app))
                .as("the subscription is an open async request")
                .isEqualTo(1);

        Duration shutdown = timeClose(app);

        assertThat(shutdown).isLessThan(PROMPT_SHUTDOWN);
        // A clean end of stream, not a reset connection, makes EventSource reconnect with its cursor.
        assertThat(remainingLines(input)).contains("event:stream.checkpoint");
    }

    @Test
    void mcpOpensNoListeningStreamForShutdownToWaitOn() throws Exception {
        var app = start("mcp-stream.db");
        var listening = http.send(
                HttpRequest.newBuilder(URI.create(base(app) + "/mcp"))
                        .timeout(Duration.ofSeconds(5))
                        .header("Accept", "text/event-stream")
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(listening.statusCode()).isEqualTo(405);
        assertThat(openStreams(app)).isZero();
        assertThat(timeClose(app)).isLessThan(PROMPT_SHUTDOWN);
    }

    @Test
    void reconnectAfterRestartResumesAfterTheLastDeliveredEvent() throws Exception {
        var before = start("resume.db");
        var stream = http.send(eventStream(before, null), HttpResponse.BodyHandlers.ofInputStream());
        var input = new BufferedReader(new InputStreamReader(stream.body(), StandardCharsets.UTF_8));
        String delivered = capture(before, "resume-before", "Delivered before the restart");
        String lastEventId = cursorOf(input, delivered);

        assertThat(timeClose(before)).isLessThan(PROMPT_SHUTDOWN);
        assertThat(remainingLines(input)).doesNotContain(delivered);

        var after = start("resume.db");
        String missed = capture(after, "resume-after", "Captured while the client was away");
        var resumed = http.send(eventStream(after, lastEventId), HttpResponse.BodyHandlers.ofInputStream());
        var replay = new BufferedReader(new InputStreamReader(resumed.body(), StandardCharsets.UTF_8));
        String frames = readThrough(replay, "\"id\":\"" + missed + "\"");

        assertThat(frames).contains(missed).doesNotContain(delivered).doesNotContain("replay.reset");
        resumed.body().close();
    }

    private ServletWebServerApplicationContext start(String database) {
        var app = (ServletWebServerApplicationContext) new SpringApplicationBuilder(SbaAgenticApplication.class)
                .run(
                        "--spring.config.location=classpath:/application.yml",
                        "--spring.profiles.active=default",
                        "--spring.datasource.url=jdbc:sqlite:" + directory.resolve(database),
                        "--server.address=127.0.0.1",
                        "--server.port=0",
                        "--spring.lifecycle.timeout-per-shutdown-phase=" + PHASE_TIMEOUT,
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
                        "--logging.level.root=WARN",
                        // Name the phase that waited and the requests it abandoned when this fails.
                        "--logging.level.org.springframework.boot.web.embedded.tomcat.GracefulShutdown=INFO",
                        "--logging.level.org.springframework.context.support.DefaultLifecycleProcessor=INFO");
        started.add(app);

        return app;
    }

    private static Duration timeClose(ServletWebServerApplicationContext app) {
        long begin = System.nanoTime();
        app.close();

        return Duration.ofNanos(System.nanoTime() - begin);
    }

    /** The count Spring Boot's Tomcat graceful shutdown polls until it reaches zero. */
    private static int openStreams(ServletWebServerApplicationContext app) {
        var tomcat = ((TomcatWebServer) app.getWebServer()).getTomcat();
        int open = 0;
        for (Container host : tomcat.getEngine().findChildren()) {
            for (Container context : host.findChildren()) {
                open += ((StandardContext) context).getInProgressAsyncCount();
            }
        }

        return open;
    }

    private static String base(ServletWebServerApplicationContext app) {

        return "http://127.0.0.1:" + app.getWebServer().getPort();
    }

    private static HttpRequest eventStream(ServletWebServerApplicationContext app, String lastEventId) {
        var request = HttpRequest.newBuilder(URI.create(base(app) + "/api/stream"))
                .header("Accept", "text/event-stream")
                .GET();
        if (lastEventId != null) request.header("Last-Event-ID", lastEventId);

        return request.build();
    }

    private String capture(ServletWebServerApplicationContext app, String clientSessionId, String text)
            throws Exception {
        var response = http.send(
                HttpRequest.newBuilder(URI.create(base(app) + "/api/events"))
                        .timeout(Duration.ofSeconds(20))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of(
                                "source", "codex",
                                "clientSessionId", clientSessionId,
                                "eventType", "Observation",
                                "role", "assistant",
                                "text", text))))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);

        return json.readTree(response.body()).path("eventId").asText();
    }

    /** Reads frames until the event arrives and returns the opaque SSE ID that delivered it. */
    private String cursorOf(BufferedReader input, String eventId) throws Exception {
        String id = null;
        String line;
        while ((line = input.readLine()) != null) {
            if (line.startsWith("id:")) id = line.substring(3);
            if (line.startsWith("data:")) {
                JsonNode data = json.readTree(line.substring(5));
                if (eventId.equals(data.path("id").asText()))

                    return id;
            }
        }
        throw new AssertionError("Stream ended before event " + eventId);
    }

    /** Everything the server sent before ending the stream; fails if the connection was reset. */
    private static String remainingLines(BufferedReader input) {
        var rest = new StringBuilder();
        try {
            String line;
            while ((line = input.readLine()) != null) rest.append(line).append('\n');
        } catch (IOException reset) {
            throw new AssertionError("The stream was cut off instead of ending: " + reset, reset);
        }

        return rest.toString();
    }

    private static String readThrough(BufferedReader input, String marker) throws Exception {
        var frames = new StringBuilder();
        String line;
        while ((line = input.readLine()) != null) {
            frames.append(line).append('\n');
            if (line.contains(marker))

                return frames.toString();
        }
        throw new AssertionError("Stream ended before " + marker + ":\n" + frames);
    }
}
