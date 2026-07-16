package dev.nathan.sbaagentic.runner.run;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nathan.sbaagentic.event.IngestResponse;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class WorkerSessionIngestTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void findsNewestRolloutWhoseCanonicalCwdMatches() throws Exception {
        FakeBlackBoxApiClient apiClient = new FakeBlackBoxApiClient();
        WorkerSessionIngest ingest = new WorkerSessionIngest(apiClient, objectMapper);
        Path worktree = Files.createDirectories(tempDir.resolve("worktree"));
        Path sessions = Files.createDirectories(tempDir.resolve("sessions/2026/07/15"));

        Path older = writeRollout(sessions.resolve("older.jsonl"), worktree, "session-old");
        Path newer = writeRollout(sessions.resolve("newer.jsonl"), worktree, "session-new");
        Path mismatch = writeRollout(
                sessions.resolve("mismatch.jsonl"),
                Files.createDirectories(tempDir.resolve("other")),
                "session-other");
        Files.setLastModifiedTime(older, FileTime.fromMillis(1_000));
        Files.setLastModifiedTime(newer, FileTime.fromMillis(2_000));
        Files.setLastModifiedTime(mismatch, FileTime.fromMillis(3_000));

        Optional<Path> found = ingest.findLatestMatchingRollout(
                sessions.getParent().getParent().getParent(),
                worktree.toFile().getCanonicalPath());

        assertThat(found).contains(newer);
    }

    @Test
    void returnsEmptyWhenSessionsRootIsMissingOrNoCwdMatches() throws Exception {
        WorkerSessionIngest ingest = new WorkerSessionIngest(
                new FakeBlackBoxApiClient(), objectMapper);
        Path worktree = Files.createDirectories(tempDir.resolve("worktree"));
        Path sessions = Files.createDirectories(tempDir.resolve("sessions"));
        writeRollout(
                sessions.resolve("mismatch.jsonl"),
                Files.createDirectories(tempDir.resolve("other")),
                "session-other");

        assertThat(ingest.findLatestMatchingRollout(
                        tempDir.resolve("missing"), worktree.toFile().getCanonicalPath()))
                .isEmpty();
        assertThat(ingest.findLatestMatchingRollout(
                        sessions, worktree.toFile().getCanonicalPath()))
                .isEmpty();
    }

    @Test
    void ingestsLinesThenLinksAndAnnotatesWithLoadBearingSessionIdKey() throws Exception {
        FakeBlackBoxApiClient apiClient = new FakeBlackBoxApiClient();
        IngestResponse response = new IngestResponse(
                "event-1", "worker-internal-1", "codex", "worker-client-1", "session_meta", false);
        apiClient.ingestResponse = response;
        WorkerSessionIngest ingest = new WorkerSessionIngest(apiClient, objectMapper);
        Path worktree = Files.createDirectories(tempDir.resolve("worktree"));
        Path sessions = Files.createDirectories(tempDir.resolve("sessions/2026/07/15"));
        Path rollout = writeRollout(sessions.resolve("rollout.jsonl"), worktree, "worker-client-1");
        Files.writeString(
                rollout,
                Files.readString(rollout)
                        + "{\"timestamp\":\"2026-07-15T12:01:00Z\","
                        + "\"type\":\"response_item\",\"payload\":{\"role\":\"assistant\","
                        + "\"content\":[{\"type\":\"output_text\",\"text\":\"hello\"}]}}\n");

        Optional<String> result = ingest.ingestAndLink(
                sessions.getParent().getParent().getParent(),
                worktree.toFile(),
                "task-1",
                "blackbox-runner",
                "orchestrator-1");

        assertThat(result).contains("worker-internal-1");
        assertThat(apiClient.postEventCalls).hasSize(2);
        assertThat(apiClient.postEventCalls.getFirst())
                .extracting(
                        FakeBlackBoxApiClient.PostEventCall::source,
                        FakeBlackBoxApiClient.PostEventCall::clientSessionId,
                        FakeBlackBoxApiClient.PostEventCall::eventType,
                        FakeBlackBoxApiClient.PostEventCall::cwd)
                .containsExactly(
                        "codex",
                        "worker-client-1",
                        "session_meta",
                        worktree.toFile().getCanonicalPath());
        assertThat(apiClient.postEventCalls)
                .allSatisfy(call -> {
                    assertThat(call.metadata().get("title")).isEqualTo("Codex worker session");
                    assertThat(call.metadata()).containsKey("raw");
                    assertThat(call.observedAt()).isInstanceOf(Instant.class);
                });
        assertThat(apiClient.postEventCalls.get(1).text()).isEqualTo("hello");
        assertThat(apiClient.sessionLinkCalls).containsExactly(
                new FakeBlackBoxApiClient.SessionLinkCall(
                        "orchestrator-1", "worker-internal-1", "spawned", "task-1"));
        assertThat(apiClient.annotationCalls).containsExactly(
                new FakeBlackBoxApiClient.AnnotationCall(
                        "task-1",
                        "blackbox-runner",
                        "worker_session",
                        "Worker session ingested.",
                        Map.of("sessionId", "worker-internal-1")));
    }

    @Test
    void ingestsOnlyNewRolloutLinesAcrossPeriodicAndCompletionPasses() throws Exception {
        FakeBlackBoxApiClient apiClient = new FakeBlackBoxApiClient();
        IngestResponse response = new IngestResponse(
                "event-1", "worker-internal-1", "codex", "worker-client-1", "session_meta", false);
        apiClient.ingestResponse = response;
        WorkerSessionIngest ingest = new WorkerSessionIngest(apiClient, objectMapper);
        Path worktree = Files.createDirectories(tempDir.resolve("worktree"));
        Path sessions = Files.createDirectories(tempDir.resolve("sessions/2026/07/15"));
        Path rollout = writeRollout(sessions.resolve("rollout.jsonl"), worktree, "worker-client-1");
        Files.writeString(
                rollout,
                "{\"timestamp\":\"2026-07-15T12:01:00Z\","
                        + "\"type\":\"response_item\",\"payload\":{\"role\":\"assistant\","
                        + "\"content\":[{\"type\":\"output_text\",\"text\":\"first\"}]}}\n",
                StandardOpenOption.APPEND);

        Optional<String> firstResult = ingest.ingestIncremental(
                sessions.getParent().getParent().getParent(),
                worktree.toFile(),
                "task-1",
                "blackbox-runner",
                "orchestrator-1");

        assertThat(firstResult).contains("worker-internal-1");
        assertThat(apiClient.postEventCalls).hasSize(2);
        assertThat(apiClient.sessionLinkCalls).hasSize(1);
        assertThat(apiClient.annotationCalls).hasSize(1);

        Files.writeString(
                rollout,
                "{\"timestamp\":\"2026-07-15T12:02:00Z\","
                        + "\"type\":\"response_item\",\"payload\":{\"role\":\"assistant\","
                        + "\"content\":[{\"type\":\"output_text\",\"text\":\"second\"}]}}\n",
                StandardOpenOption.APPEND);

        Optional<String> secondResult = ingest.ingestIncremental(
                sessions.getParent().getParent().getParent(),
                worktree.toFile(),
                "task-1",
                "blackbox-runner",
                "orchestrator-1");

        assertThat(secondResult).contains("worker-internal-1");
        assertThat(apiClient.postEventCalls).hasSize(3);
        assertThat(apiClient.postEventCalls.getLast().text()).isEqualTo("second");
        assertThat(apiClient.sessionLinkCalls).hasSize(1);
        assertThat(apiClient.annotationCalls).hasSize(1);

        Optional<String> finalResult = ingest.ingestAndLink(
                sessions.getParent().getParent().getParent(),
                worktree.toFile(),
                "task-1",
                "blackbox-runner",
                "orchestrator-1");

        assertThat(finalResult).contains("worker-internal-1");
        assertThat(apiClient.postEventCalls).hasSize(3);
        assertThat(apiClient.sessionLinkCalls).hasSize(1);
        assertThat(apiClient.annotationCalls).hasSize(1);
    }

    private static Path writeRollout(Path path, Path cwd, String sessionId) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(
                path,
                "{\"timestamp\":\"2026-07-15T12:00:00Z\",\"type\":\"session_meta\","
                        + "\"payload\":{\"session_id\":\"" + sessionId + "\",\"cwd\":\""
                        + cwd.toFile().getCanonicalPath().replace("\\", "\\\\").replace("\"", "\\\"")
                        + "\"}}\n");
        return path;
    }
}
