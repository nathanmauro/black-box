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
import java.util.concurrent.TimeUnit;

import dev.nathan.sbaagentic.recording.EventIngestRequest;
import dev.nathan.sbaagentic.recording.EventRecorder;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * End-to-end check that a newly ingested event is pushed to a live {@code /api/stream} subscriber as
 * an {@code event.appended} Server-Sent Event.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        // A temp file DB takes the production WAL + busy_timeout path; cache=shared
        // memory throws SQLITE_LOCKED on writer collisions, ignoring busy_timeout.
        "spring.datasource.url=jdbc:sqlite:${java.io.tmpdir}/bb-event-stream-test-${random.uuid}.db",
        "sba.local-ai.enabled=false",
        "sba.summary.backend=local",
        "sba.elasticsearch.enabled=false",
        "sba.memory.embedding.enabled=false",
        "server.shutdown=immediate"
})
class EventStreamTest {

    @LocalServerPort
    int port;

    @Autowired
    EventRecorder ingestService;

    @Autowired
    EventBroadcaster broadcaster;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void idleStreamReceivesHeartbeatBeforeProxyTimeoutWithoutNewEvents() throws Exception {
        var client = HttpClient.newHttpClient();
        var response = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/stream"))
                .header("Accept", "text/event-stream").GET().build(), HttpResponse.BodyHandlers.ofInputStream());
        var reader = Executors.newSingleThreadExecutor(runnable -> {
            var thread = new Thread(runnable, "sse-idle-test-reader");
            thread.setDaemon(true);
            return thread;
        });
        var input = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8));
        try {
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(input.readLine()).isEqualTo(":connected");
            assertThat(input.readLine()).isEmpty();
            // No ingestion or manual scheduler invocation: this is an actual idle HTTP socket.
            var nextLine = reader.submit(input::readLine);
            assertThat(nextLine.get(20, TimeUnit.SECONDS)).isEqualTo(":heartbeat");
            assertThat(input.readLine()).isEmpty();
        } finally {
            // Close the underlying response first: BufferedReader.close waits on a blocked read lock.
            response.body().close();
            reader.shutdownNow();
            client.shutdownNow();
        }
    }

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
                            .contains("\"cwd\":\"/tmp/project\"")
                            .contains("\"role\":\"assistant\"")
                            .contains("\"textPreview\":\"This decision is pushed over SSE.\""));
        } finally {
            pump.cancel(true);
            reader.shutdownNow();
            client.shutdownNow(); // release the kept-alive SSE connection and HttpClient threads
        }
    }

    @Test
    void streamReplaysSinceOldestFirstWithSseCursorIds() throws Exception {
        insertReplayFixture("replay-session", "replay-event-1", "2026-09-21T12:00:00Z", "first");
        insertReplayFixture("replay-session", "replay-event-2", "2026-09-21T12:00:01Z", "second");

        HttpClient client = HttpClient.newHttpClient();
        var response = client.send(HttpRequest.newBuilder(URI.create(
                        "http://localhost:" + port + "/api/stream?since=2026-09-21T11:59:59Z"))
                .header("Accept", "text/event-stream")
                .GET()
                .build(), HttpResponse.BodyHandlers.ofInputStream());
        try (BufferedReader in = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String replay = readLines(in, 10);
            assertThat(replay)
                    .contains("id:2026-09-21T12:00:00Z|replay-event-1")
                    .contains("id:2026-09-21T12:00:01Z|replay-event-2")
                    .contains("\"textPreview\":\"first\"")
                    .contains("\"textPreview\":\"second\"");
            assertThat(replay.indexOf("replay-event-1")).isLessThan(replay.indexOf("replay-event-2"));
        } finally {
            response.body().close();
            client.shutdownNow();
        }
    }

    @Test
    void lastEventIdReplaysAfterCursorExclusively() throws Exception {
        insertReplayFixture("cursor-session", "cursor-event-1", "2026-09-21T12:10:00Z", "first");
        insertReplayFixture("cursor-session", "cursor-event-2", "2026-09-21T12:10:01Z", "second");

        HttpClient client = HttpClient.newHttpClient();
        var response = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/stream"))
                .header("Accept", "text/event-stream")
                .header("Last-Event-ID", "2026-09-21T12:10:00Z|cursor-event-1")
                .GET()
                .build(), HttpResponse.BodyHandlers.ofInputStream());
        try (BufferedReader in = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String replay = readLines(in, 6);
            assertThat(replay).doesNotContain("cursor-event-1");
            assertThat(replay).contains("cursor-event-2");
        } finally {
            response.body().close();
            client.shutdownNow();
        }
    }

    private void insertReplayFixture(String sessionId, String eventId, String observedAt, String text) {
        jdbcTemplate.update("""
                INSERT INTO agent_sessions (
                    id, source, client_session_id, title, title_rank, cwd, started_at, last_seen_at, event_count
                )
                VALUES (?, 'codex', ?, 'Replay', 5, '/tmp/replay',
                        '2026-09-21T12:00:00Z', ?, 1)
                ON CONFLICT (source, client_session_id) DO UPDATE SET last_seen_at = excluded.last_seen_at
                """, sessionId, sessionId + "-client", observedAt);
        jdbcTemplate.update("""
                INSERT INTO agent_events (
                    id, session_id, source, client_session_id, event_type, role, text, observed_at
                )
                VALUES (?, ?, 'codex', ?, 'Decision', 'assistant', ?, ?)
                """, eventId, sessionId, sessionId + "-client", text, observedAt);
    }

    private static String readLines(BufferedReader in, int maxLines) throws Exception {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < maxLines; i++) {
            String line = in.readLine();
            if (line == null) {
                break;
            }
            out.append(line).append('\n');
        }
        return out.toString();
    }
}
