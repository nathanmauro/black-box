package dev.nathan.sbaagentic.stream;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;

import dev.nathan.sbaagentic.task.ClaimTaskRequest;
import dev.nathan.sbaagentic.task.CreateSpecRequest;
import dev.nathan.sbaagentic.task.EnqueueTaskRequest;
import dev.nathan.sbaagentic.task.TaskService;
import dev.nathan.sbaagentic.task.TaskSpec;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:sqlite:target/task-stream-test.db",
        "sba.local-ai.enabled=false",
        "sba.summary.backend=local",
        "sba.elasticsearch.enabled=false",
        "server.shutdown=immediate"
})
class TaskStreamTest {

    @LocalServerPort
    int port;

    @Autowired
    TaskService taskService;

    @Autowired
    EventBroadcaster broadcaster;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetQueue() {
        jdbcTemplate.update("DELETE FROM task_events");
        jdbcTemplate.update("DELETE FROM tasks");
        jdbcTemplate.update("DELETE FROM specs");
    }

    @Test
    void committedTaskChangesUseNamedSseFramesWithCurrentTaskAndTransition() {
        TaskSpec spec = taskService.createSpec(new CreateSpecRequest(
                "/repos/task-stream-test", "SSE", "Verify task frames.", Map.of(), "planner"));
        BlockingQueue<String> lines = new LinkedBlockingQueue<>();
        ExecutorService reader = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "task-sse-test-reader");
            thread.setDaemon(true);
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
                // connection closed by test teardown
            }
        });

        try {
            await().atMost(Duration.ofSeconds(5)).until(() -> broadcaster.subscriberCount() >= 1);

            String taskId = taskService.enqueueTask(new EnqueueTaskRequest(
                    spec.id(), "streamed task", "codex", 4, "planner")).snapshot().task().id();
            taskService.claimNextTask(new ClaimTaskRequest("codex", "agent-a")).orElseThrow();

            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                String stream = String.join("\n", lines);
                assertThat(stream)
                        .contains("event:task.created")
                        .contains("event:task.claimed")
                        .contains("\"transitionType\":\"task.created\"")
                        .contains("\"transitionType\":\"task.claimed\"")
                        .contains("\"transitionId\":")
                        .contains("\"id\":\"" + taskId + "\"")
                        .contains("\"status\":\"open\"")
                        .contains("\"status\":\"in_progress\"");
            });
        } finally {
            pump.cancel(true);
            reader.shutdownNow();
            client.shutdownNow();
        }
    }
}
