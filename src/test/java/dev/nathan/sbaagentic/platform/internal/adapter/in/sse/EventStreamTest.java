package dev.nathan.sbaagentic.platform.internal.adapter.in.sse;


import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;

import dev.nathan.sbaagentic.recording.EventIngestRequest;
import dev.nathan.sbaagentic.recording.EventRecorder;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * End-to-end check that a newly ingested event is pushed to a live {@code /api/stream} subscriber as
 * an {@code event.appended} Server-Sent Event.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:sqlite:file:event-stream-test?mode=memory&cache=shared",
        "sba.local-ai.enabled=false",
        "sba.summary.backend=local",
        "sba.elasticsearch.enabled=false",
        "server.shutdown=immediate"
})
class EventStreamTest {

    @LocalServerPort
    int port;

    @Autowired
    EventRecorder ingestService;

    @Autowired
    EventBroadcaster broadcaster;

    @Test
    void newEventIsPushedToSubscriber() {
        BlockingQueue<String> lines = new LinkedBlockingQueue<>();
        ExecutorService reader = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "sse-test-reader");
            thread.setDaemon(true); // must not keep the surefire fork JVM alive on a blocking read
            return thread;
        });
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/stream"))
                .header("Accept", "text/event-stream")
                .GET()
                .build();

        Future<?> pump = reader.submit(() -> {
            try {
                HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
                try (BufferedReader in = new BufferedReader(
                        new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = in.readLine()) != null) {
                        lines.add(line);
                    }
                }
            } catch (Exception ignored) {
                // connection closed by the test teardown
            }
        });

        try {
            // The subscriber must be registered before we ingest — SSE does not replay missed frames.
            await().atMost(Duration.ofSeconds(5)).until(() -> broadcaster.subscriberCount() >= 1);

            ingestService.ingest(new EventIngestRequest(
                    "codex", "stream-session", "turn-1", "Decision", "assistant",
                    "This decision is pushed over SSE.", "/tmp/project", null, null, null,
                    Map.of("title", "sse push"),
                    Instant.parse("2026-06-16T12:00:00Z")));

            await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertThat(String.join("\n", lines))
                            .contains("event.appended")
                            .contains("\"id\":")
                            .contains("\"source\":\"codex\"")
                            .contains("\"cwd\":\"/tmp/project\""));
        } finally {
            pump.cancel(true);
            reader.shutdownNow();
            client.shutdownNow(); // release the kept-alive SSE connection and HttpClient threads
        }
    }
}
