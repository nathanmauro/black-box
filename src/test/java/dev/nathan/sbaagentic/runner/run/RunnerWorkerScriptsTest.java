package dev.nathan.sbaagentic.runner.run;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.nathan.sbaagentic.runner.RunnerNaming;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class RunnerWorkerScriptsTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String TASK_ID = "12345678-abcd-4abc-8abc-1234567890ab";

    @TempDir
    Path tempDir;

    @Test
    void reportKeepsDoneAndBlockedPayloadsAndAddsPlanAndReviewKinds() throws Exception {
        assertThat(runReport("done", "worker completed")).isEqualTo(OBJECT_MAPPER.readTree("""
                {
                  "actor": "blackbox-runner-worker",
                  "kind": "progress",
                  "text": "worker completed",
                  "dataJson": {"event": "worker_done", "outcome": "done"}
                }
                """));
        assertThat(runReport("blocked", "worker stopped")).isEqualTo(OBJECT_MAPPER.readTree("""
                {
                  "actor": "blackbox-runner-worker",
                  "kind": "progress",
                  "text": "worker stopped",
                  "dataJson": {"event": "worker_done", "outcome": "blocked"}
                }
                """));
        assertThat(runReport("plan", "plan markdown")).isEqualTo(OBJECT_MAPPER.readTree("""
                {
                  "actor": "blackbox-runner-worker",
                  "kind": "plan",
                  "text": "plan markdown"
                }
                """));
        assertThat(runReport("review", "review markdown")).isEqualTo(OBJECT_MAPPER.readTree("""
                {
                  "actor": "blackbox-runner-worker",
                  "kind": "review",
                  "text": "review markdown"
                }
                """));
    }

    @Test
    void fakePlanAndReviewStagesPostStageArtifactThenDoneWithoutMutatingWorktree()
            throws Exception {
        assertReadOnlyFakeStage("plan", "plan");
        assertReadOnlyFakeStage("review", "review");
    }

    private JsonNode runReport(String verb, String text) throws Exception {
        try (CaptureServer server = new CaptureServer(1)) {
            ProcessResult result = run(
                    List.of(
                            "/bin/bash",
                            RunnerNaming.scriptPath("scripts/runner/report.sh"),
                            TASK_ID,
                            verb,
                            text),
                    tempDir,
                    Map.of("SBA_BASE_URL", server.baseUrl()));

            assertThat(result.exitCode()).as(result.stderr()).isZero();
            assertThat(server.await(Duration.ofSeconds(5))).isTrue();
            return OBJECT_MAPPER.readTree(server.bodies().getFirst());
        }
    }

    private void assertReadOnlyFakeStage(String stage, String expectedKind) throws Exception {
        Path worktree = Files.createDirectories(tempDir.resolve("worktree-" + stage));
        Path home = Files.createDirectories(tempDir.resolve("home-" + stage));
        try (CaptureServer server = new CaptureServer(2)) {
            ProcessResult result = run(
                    List.of(
                            "/bin/bash",
                            RunnerNaming.scriptPath("scripts/runner/fake-worker.sh")),
                    worktree,
                    Map.of(
                            "HOME", home.toString(),
                            "SBA_BASE_URL", server.baseUrl(),
                            "SBA_STAGE", stage,
                            "SBA_TASK_ID", TASK_ID,
                            "SBA_WORKTREE", worktree.toString()));

            assertThat(result.exitCode()).as(result.stderr()).isZero();
            assertThat(server.await(Duration.ofSeconds(5))).isTrue();
            try (var children = Files.list(worktree)) {
                assertThat(children.toList()).isEmpty();
            }
            assertThat(server.bodies()).hasSize(2);
            assertThat(OBJECT_MAPPER.readTree(server.bodies().get(0)).path("kind").asText())
                    .isEqualTo(expectedKind);
            JsonNode done = OBJECT_MAPPER.readTree(server.bodies().get(1));
            assertThat(done.path("kind").asText()).isEqualTo("progress");
            assertThat(done.at("/dataJson/event").asText()).isEqualTo("worker_done");
            assertThat(done.at("/dataJson/outcome").asText()).isEqualTo("done");
            assertThat(home.resolve(".codex/sessions/blackbox-e2e/" + TASK_ID + ".jsonl"))
                    .isRegularFile();
        }
    }

    private static ProcessResult run(
            List<String> command,
            Path workingDirectory,
            Map<String, String> environment) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command).directory(workingDirectory.toFile());
        builder.environment().putAll(environment);
        Process process = builder.start();
        boolean finished = process.waitFor(10, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
        }
        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        return new ProcessResult(finished ? process.exitValue() : -1, stdout, stderr);
    }

    private record ProcessResult(int exitCode, String stdout, String stderr) {
    }

    private static final class CaptureServer implements AutoCloseable {

        private final HttpServer server;
        private final CountDownLatch requests;
        private final List<String> bodies = new ArrayList<>();

        private CaptureServer(int expectedRequests) throws IOException {
            requests = new CountDownLatch(expectedRequests);
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/tasks/" + TASK_ID + "/annotations", this::capture);
            server.start();
        }

        private String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        private List<String> bodies() {
            return List.copyOf(bodies);
        }

        private boolean await(Duration timeout) throws InterruptedException {
            return requests.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        private void capture(HttpExchange exchange) throws IOException {
            synchronized (bodies) {
                bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            }
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            requests.countDown();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
