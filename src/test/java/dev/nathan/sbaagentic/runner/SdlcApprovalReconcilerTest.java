package dev.nathan.sbaagentic.runner;

import dev.nathan.sbaagentic.runner.internal.client.blackbox.BlackBoxApiClient;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nathan.sbaagentic.runner.gate.StoryFrontmatterParser;
import dev.nathan.sbaagentic.runner.process.ProcessRunner;
import dev.nathan.sbaagentic.runner.process.ProcessRunner.ProcessResult;
import dev.nathan.sbaagentic.runner.ship.ShipExecutor;
import dev.nathan.sbaagentic.runner.ship.ShipExecutor.ShipResult;
import dev.nathan.sbaagentic.runner.internal.client.blackbox.SpecStatus;
import dev.nathan.sbaagentic.runner.internal.client.blackbox.Task;
import dev.nathan.sbaagentic.runner.internal.client.blackbox.TaskAnnotation;
import dev.nathan.sbaagentic.runner.internal.client.blackbox.TaskChange;
import dev.nathan.sbaagentic.runner.internal.client.blackbox.TaskEvent;
import dev.nathan.sbaagentic.runner.internal.client.blackbox.TaskEventType;
import dev.nathan.sbaagentic.runner.internal.client.blackbox.TaskSnapshot;
import dev.nathan.sbaagentic.runner.internal.client.blackbox.TaskSpec;
import dev.nathan.sbaagentic.runner.internal.client.blackbox.TaskStatus;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class SdlcApprovalReconcilerTest {

    private static final String ACTOR = "blackbox-runner";
    private static final String WORKER_ACTOR = "blackbox-runner-worker";
    private static final String SPEC_ID = "spec-1";
    private static final String BUILD_BRANCH = RunExecutor.branchName("Story task", "build-1");
    private static final Instant NOW = Instant.parse("2026-07-16T12:00:00Z");

    @TempDir
    Path tempDir;

    @Test
    void planApprovalEnqueuesBuildExactlyOnceAcrossRestartAndReplay() throws Exception {
        Path repo = Files.createDirectories(tempDir.resolve("repo"));
        RecordingApiClient apiClient = new RecordingApiClient();
        TaskSnapshot plan = task(repo, "plan-1", "sdlc:plan", TaskStatus.DONE, "sdlc");
        apiClient.tasks.add(plan);
        apiClient.events.put("plan-1", new ArrayList<>(List.of(
                workerArtifact("plan-artifact", "plan-1", "plan", "Implementation plan."),
                approval("approval-1", "plan-1", "approve", "plan", ""))));
        ShipExecutor shipExecutor = mock(ShipExecutor.class);
        RecordingProcessRunner processRunner = new RecordingProcessRunner(BUILD_BRANCH);

        reconciler(apiClient, shipExecutor, processRunner)
                .reconcileTask("plan-1", config(repo), ACTOR);
        reconciler(apiClient, shipExecutor, processRunner)
                .reconcileTask("plan-1", config(repo), ACTOR);
        reconciler(apiClient, shipExecutor, processRunner)
                .reconcile(config(repo), ACTOR);

        assertThat(apiClient.enqueues).singleElement().satisfies(enqueue -> {
            assertThat(enqueue.specId()).isEqualTo(SPEC_ID);
            assertThat(enqueue.lane()).isEqualTo("auto");
            assertThat(enqueue.priority()).isEqualTo(10);
        });
        verify(shipExecutor, never()).shipForSdlc(
                anyString(), anyString(), any(), anyString(), any(), anyString(), anyString(), anyString());
    }

    @Test
    void blockedBuildSuccessorCountsAsActedOnAndIsNeverReEnqueued() throws Exception {
        Path repo = Files.createDirectories(tempDir.resolve("repo"));
        RecordingApiClient apiClient = new RecordingApiClient();
        TaskSnapshot plan = task(repo, "plan-1", "sdlc:plan", TaskStatus.DONE, "sdlc");
        TaskSnapshot blockedBuild = task(repo, "build-1", "auto", TaskStatus.BLOCKED, "sdlc");
        apiClient.tasks.add(plan);
        apiClient.tasks.add(blockedBuild);
        apiClient.events.put("plan-1", new ArrayList<>(List.of(
                workerArtifact("plan-artifact", "plan-1", "plan", "Implementation plan."),
                approval("approval-1", "plan-1", "approve", "plan", ""))));
        ShipExecutor shipExecutor = mock(ShipExecutor.class);
        RecordingProcessRunner processRunner = new RecordingProcessRunner(BUILD_BRANCH);

        reconciler(apiClient, shipExecutor, processRunner)
                .reconcileTask("plan-1", config(repo), ACTOR);
        reconciler(apiClient, shipExecutor, processRunner)
                .reconcile(config(repo), ACTOR);

        assertThat(apiClient.enqueues).isEmpty();
    }

    @Test
    void planApprovalWithoutWorkerPlanFailsClosed() throws Exception {
        Path repo = Files.createDirectories(tempDir.resolve("repo"));
        RecordingApiClient apiClient = new RecordingApiClient();
        apiClient.tasks.add(task(repo, "plan-1", "sdlc:plan", TaskStatus.DONE, "sdlc"));
        apiClient.events.put("plan-1", new ArrayList<>(List.of(
                approval("approval-1", "plan-1", "approve", "plan", ""))));

        reconciler(
                        apiClient,
                        mock(ShipExecutor.class),
                        new RecordingProcessRunner(BUILD_BRANCH))
                .reconcileTask("plan-1", config(repo), ACTOR);

        assertThat(apiClient.enqueues).isEmpty();
    }

    @Test
    void completedBuildMissingReviewIsReconciledExactlyOnce() throws Exception {
        Path repo = Files.createDirectories(tempDir.resolve("repo"));
        Path worktree = Files.createDirectories(repo.resolve(".worktrees/bb-build-1"));
        RecordingApiClient apiClient = new RecordingApiClient();
        apiClient.tasks.add(task(repo, "build-1", "auto", TaskStatus.DONE, "sdlc"));
        apiClient.events.put("build-1", new ArrayList<>(List.of(
                buildArtifact("build-1", worktree),
                workerDone("build-1"))));

        reconciler(
                        apiClient,
                        mock(ShipExecutor.class),
                        new RecordingProcessRunner(BUILD_BRANCH))
                .reconcile(config(repo), ACTOR);
        reconciler(
                        apiClient,
                        mock(ShipExecutor.class),
                        new RecordingProcessRunner(BUILD_BRANCH))
                .reconcile(config(repo), ACTOR);

        assertThat(apiClient.enqueues).singleElement().satisfies(enqueue -> {
            assertThat(enqueue.specId()).isEqualTo(SPEC_ID);
            assertThat(enqueue.lane()).isEqualTo("sdlc:review");
        });
    }

    @Test
    void rejectionIsRecordedOnceAndPermanentlyBlocksTheStage() throws Exception {
        Path repo = Files.createDirectories(tempDir.resolve("repo"));
        RecordingApiClient apiClient = new RecordingApiClient();
        TaskSnapshot plan = task(repo, "plan-1", "sdlc:plan", TaskStatus.DONE, "sdlc");
        apiClient.tasks.add(plan);
        apiClient.events.put("plan-1", new ArrayList<>(List.of(
                approval("approval-reject", "plan-1", "reject", "plan", "Tighten rollback checks."))));
        ShipExecutor shipExecutor = mock(ShipExecutor.class);
        RecordingProcessRunner processRunner = new RecordingProcessRunner(BUILD_BRANCH);

        reconciler(apiClient, shipExecutor, processRunner)
                .reconcileTask("plan-1", config(repo), ACTOR);
        apiClient.events.get("plan-1").add(
                approval("approval-late", "plan-1", "approve", "plan", ""));
        reconciler(apiClient, shipExecutor, processRunner)
                .reconcileTask("plan-1", config(repo), ACTOR);
        reconciler(apiClient, shipExecutor, processRunner)
                .reconcile(config(repo), ACTOR);

        assertThat(apiClient.enqueues).isEmpty();
        assertThat(apiClient.annotations)
                .filteredOn(annotation -> "rejection_recorded".equals(annotation.dataJson().get("sdlc")))
                .singleElement()
                .satisfies(annotation -> {
                    assertThat(annotation.taskId()).isEqualTo("plan-1");
                    assertThat(annotation.text()).contains("Tighten rollback checks.");
                    assertThat(annotation.dataJson())
                            .containsEntry("approvalId", "approval-reject")
                            .containsEntry("stage", "plan")
                            .containsEntry("decision", "reject");
                });
        verify(shipExecutor, never()).shipForSdlc(
                anyString(), anyString(), any(), anyString(), any(), anyString(), anyString(), anyString());
    }

    @Test
    void reviewApprovalShipsPreservedBuildExactlyOnce() throws Exception {
        Path repo = Files.createDirectories(tempDir.resolve("repo"));
        Path worktree = Files.createDirectories(repo.resolve(".worktrees/bb-build-1"));
        RecordingApiClient apiClient = reviewFixture(repo, worktree, "approve");
        ShipExecutor shipExecutor = mock(ShipExecutor.class);
        doAnswer(invocation -> {
            String reviewTaskId = invocation.getArgument(0);
            String approvalId = invocation.getArgument(7);
            apiClient.appendAnnotationEvent(
                    reviewTaskId,
                    ACTOR,
                    "progress",
                    "Ship result: local only.",
                    Map.of(
                            "sdlc", "shipped",
                            "approvalId", approvalId,
                            "status", "local-only",
                            "branch", BUILD_BRANCH,
                            "worktree", worktree.toString()));
            return new ShipResult("local-only", "push disabled", null, null, List.of());
        }).when(shipExecutor).shipForSdlc(
                anyString(), anyString(), any(), anyString(), any(), anyString(), anyString(), anyString());
        RecordingProcessRunner processRunner = new RecordingProcessRunner(BUILD_BRANCH);

        reconciler(apiClient, shipExecutor, processRunner)
                .reconcileTask("review-1", config(repo), ACTOR);
        reconciler(apiClient, shipExecutor, processRunner)
                .reconcileTask("review-1", config(repo), ACTOR);
        reconciler(apiClient, shipExecutor, processRunner)
                .reconcile(config(repo), ACTOR);

        verify(shipExecutor, times(1)).shipForSdlc(
                "review-1",
                ACTOR,
                new RepoConfig(repo.toString(), false, false, "git status --short", ""),
                BUILD_BRANCH,
                worktree.toFile(),
                "Story task",
                "Review found no blocking issues.",
                "approval-review");
        assertThat(apiClient.enqueues).isEmpty();
    }

    @Test
    void reviewApprovalWithoutWorkerReviewFailsClosed() throws Exception {
        Path repo = Files.createDirectories(tempDir.resolve("repo"));
        Path worktree = Files.createDirectories(repo.resolve(".worktrees/bb-build-1"));
        RecordingApiClient apiClient = reviewFixture(repo, worktree, "approve");
        apiClient.events.put("review-1", new ArrayList<>(List.of(
                approval("approval-review", "review-1", "approve", "review", ""))));
        ShipExecutor shipExecutor = mock(ShipExecutor.class);

        reconciler(apiClient, shipExecutor, new RecordingProcessRunner(BUILD_BRANCH))
                .reconcileTask("review-1", config(repo), ACTOR);

        verify(shipExecutor, never()).shipForSdlc(
                anyString(), anyString(), any(), anyString(), any(), anyString(), anyString(), anyString());
        assertThat(apiClient.annotations).isEmpty();
    }

    @Test
    void mergedReviewApprovalPrunesOnlyTheCleanPreservedWorktree() throws Exception {
        Path repo = Files.createDirectories(tempDir.resolve("repo"));
        Path worktree = Files.createDirectories(repo.resolve(".worktrees/bb-build-1"));
        RecordingApiClient apiClient = reviewFixture(repo, worktree, "approve");
        ShipExecutor shipExecutor = mock(ShipExecutor.class);
        doAnswer(invocation -> {
            apiClient.appendAnnotationEvent(
                    "review-1",
                    ACTOR,
                    "progress",
                    "Ship result: merged.",
                    Map.of(
                            "sdlc", "shipped",
                            "approvalId", "approval-review",
                            "status", "merged",
                            "branch", BUILD_BRANCH,
                            "worktree", worktree.toString()));
            return new ShipResult(
                    "merged", "checks green", "https://example.test/pr/1", "merged", List.of());
        }).when(shipExecutor).shipForSdlc(
                anyString(), anyString(), any(), anyString(), any(), anyString(), anyString(), anyString());
        RecordingProcessRunner processRunner = new RecordingProcessRunner(BUILD_BRANCH);
        processRunner.removeWorktrees = true;

        reconciler(apiClient, shipExecutor, processRunner)
                .reconcileTask("review-1", config(repo), ACTOR);

        assertThat(worktree).doesNotExist();
        assertThat(processRunner.commands)
                .anySatisfy(command -> assertThat(command).containsSubsequence("worktree", "remove"))
                .anySatisfy(command -> assertThat(command).containsSubsequence("worktree", "prune"));
        assertThat(apiClient.annotations)
                .anySatisfy(annotation -> assertThat(annotation.dataJson())
                        .containsEntry("sdlc", "worktree_pruned"));
    }

    @Test
    void reviewRejectionNeverShipsAndNonSdlcModeNeverActs() throws Exception {
        Path repo = Files.createDirectories(tempDir.resolve("repo"));
        Path worktree = Files.createDirectories(repo.resolve(".worktrees/bb-build-1"));
        RecordingApiClient apiClient = reviewFixture(repo, worktree, "reject");
        apiClient.tasks.add(task(repo, "full-auto-plan", "sdlc:plan", TaskStatus.DONE, "full_auto"));
        apiClient.events.put("full-auto-plan", new ArrayList<>(List.of(
                approval("approval-invalid-mode", "full-auto-plan", "approve", "plan", ""))));
        ShipExecutor shipExecutor = mock(ShipExecutor.class);

        reconciler(apiClient, shipExecutor, new RecordingProcessRunner(BUILD_BRANCH))
                .reconcile(config(repo), ACTOR);

        assertThat(apiClient.enqueues).isEmpty();
        assertThat(apiClient.annotations)
                .filteredOn(annotation -> "review-1".equals(annotation.taskId()))
                .singleElement()
                .satisfies(annotation -> assertThat(annotation.dataJson())
                        .containsEntry("sdlc", "rejection_recorded")
                        .containsEntry("stage", "review"));
        verify(shipExecutor, never()).shipForSdlc(
                anyString(), anyString(), any(), anyString(), any(), anyString(), anyString(), anyString());
    }

    private SdlcApprovalReconciler reconciler(
            RecordingApiClient apiClient,
            ShipExecutor shipExecutor,
            ProcessRunner processRunner) {
        return new SdlcApprovalReconciler(
                apiClient,
                new StoryFrontmatterParser(),
                shipExecutor,
                processRunner);
    }

    private RecordingApiClient reviewFixture(Path repo, Path worktree, String decision) {
        RecordingApiClient apiClient = new RecordingApiClient();
        apiClient.tasks.add(task(repo, "build-1", "auto", TaskStatus.DONE, "sdlc"));
        apiClient.tasks.add(task(repo, "review-1", "sdlc:review", TaskStatus.DONE, "sdlc"));
        apiClient.events.put("build-1", new ArrayList<>(List.of(
                buildArtifact("build-1", worktree),
                workerDone("build-1"))));
        apiClient.events.put("review-1", new ArrayList<>(List.of(
                workerArtifact(
                        "review-findings",
                        "review-1",
                        "review",
                        "Review found no blocking issues."),
                approval(
                        "approval-review",
                        "review-1",
                        decision,
                        "review",
                        "Needs a stronger regression test."))));
        return apiClient;
    }

    private static TaskEvent buildArtifact(String taskId, Path worktree) {
        return new TaskEvent(
                "build-artifact",
                taskId,
                TaskEventType.NOTE,
                ACTOR,
                null,
                null,
                Map.of(
                        "kind", "progress",
                        "text", "Build preserved for review.",
                        "dataJson", Map.of(
                                "branch", BUILD_BRANCH,
                                "worktree", worktree.toString())),
                NOW);
    }

    private static TaskEvent workerArtifact(
            String eventId, String taskId, String kind, String text) {
        return new TaskEvent(
                eventId,
                taskId,
                TaskEventType.NOTE,
                WORKER_ACTOR,
                null,
                null,
                Map.of("kind", kind, "text", text),
                NOW.plusSeconds(1));
    }

    private static TaskEvent workerDone(String taskId) {
        return new TaskEvent(
                "worker-done",
                taskId,
                TaskEventType.NOTE,
                WORKER_ACTOR,
                null,
                null,
                Map.of(
                        "kind", "progress",
                        "text", "Worker completed.",
                        "dataJson", Map.of("event", "worker_done", "outcome", "done")),
                NOW.plusSeconds(2));
    }

    private static TaskSnapshot task(
            Path repo, String taskId, String lane, TaskStatus status, String mode) {
        Task task = new Task(
                taskId,
                SPEC_ID,
                repo.toString(),
                "Story task",
                lane,
                status,
                10,
                "nathan",
                ACTOR,
                null,
                null,
                NOW,
                NOW);
        TaskSpec spec = new TaskSpec(
                SPEC_ID,
                repo.toString(),
                "Story",
                story(repo, mode),
                null,
                SpecStatus.ACTIVE,
                "nathan",
                NOW,
                NOW);
        return new TaskSnapshot(task, spec);
    }

    private static String story(Path repo, String mode) {
        return "---\n"
                + "story: v1\n"
                + "repo: '" + repo.toString().replace("'", "''") + "'\n"
                + "mode: " + mode + "\n"
                + "verify: 'git status --short'\n"
                + "push: false\n"
                + "priority: 10\n"
                + "---\n"
                + "# Story\n\n"
                + "## Acceptance criteria\n- Work completes.\n";
    }

    private static TaskEvent approval(
            String eventId,
            String taskId,
            String decision,
            String stage,
            String feedback) {
        return new TaskEvent(
                eventId,
                taskId,
                TaskEventType.NOTE,
                "nathan",
                null,
                null,
                Map.of(
                        "kind", "approval",
                        "text", decision,
                        "dataJson", Map.of(
                                "decision", decision,
                                "stage", stage,
                                "feedback", feedback)),
                NOW.plusSeconds(2));
    }

    private static RunnerConfig config(Path repo) {
        return new RunnerConfig(
                1,
                List.of(),
                null,
                List.of(new RepoConfig(
                        repo.toString(), false, false, "git status --short", "")));
    }

    private static final class RecordingApiClient extends BlackBoxApiClient {

        private final List<TaskSnapshot> tasks = new ArrayList<>();
        private final Map<String, List<TaskEvent>> events = new LinkedHashMap<>();
        private final List<EnqueueCall> enqueues = new ArrayList<>();
        private final List<AnnotationCall> annotations = new ArrayList<>();

        private RecordingApiClient() {
            super(new ObjectMapper());
        }

        @Override
        public List<TaskSnapshot> listTasks(String status, String lane) {
            return tasks.stream()
                    .filter(snapshot -> lane == null || lane.equals(snapshot.task().lane()))
                    .filter(snapshot -> status == null || status.equals(snapshot.task().status().value()))
                    .toList();
        }

        @Override
        public List<TaskEvent> taskEvents(String taskId) {
            return List.copyOf(events.getOrDefault(taskId, List.of()));
        }

        @Override
        public TaskChange enqueueTask(
                String specId, String title, String lane, int priority, String actor) {
            enqueues.add(new EnqueueCall(specId, title, lane, priority, actor));
            TaskSnapshot source = tasks.stream()
                    .filter(snapshot -> specId.equals(snapshot.task().specId()))
                    .findFirst()
                    .orElseThrow();
            Task successor = new Task(
                    "auto-successor-" + enqueues.size(),
                    specId,
                    source.task().projectKey(),
                    title,
                    lane,
                    TaskStatus.OPEN,
                    priority,
                    actor,
                    null,
                    null,
                    null,
                    NOW,
                    NOW);
            tasks.add(new TaskSnapshot(successor, source.spec()));
            return null;
        }

        @Override
        public TaskAnnotation annotate(
                String taskId,
                String actor,
                String kind,
                String text,
                Map<String, Object> dataJson) {
            annotations.add(new AnnotationCall(taskId, actor, kind, text, dataJson));
            appendAnnotationEvent(taskId, actor, kind, text, dataJson);
            return null;
        }

        private void appendAnnotationEvent(
                String taskId,
                String actor,
                String kind,
                String text,
                Map<String, Object> dataJson) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("kind", kind);
            detail.put("text", text);
            detail.put("dataJson", dataJson);
            events.computeIfAbsent(taskId, ignored -> new ArrayList<>()).add(new TaskEvent(
                    "runner-event-" + events.get(taskId).size(),
                    taskId,
                    TaskEventType.NOTE,
                    actor,
                    null,
                    null,
                    detail,
                    NOW.plusSeconds(10 + events.get(taskId).size())));
        }
    }

    private static final class RecordingProcessRunner implements ProcessRunner {

        private final String branch;
        private final List<List<String>> commands = new ArrayList<>();
        private boolean removeWorktrees;

        private RecordingProcessRunner(String branch) {
            this.branch = branch;
        }

        @Override
        public ProcessResult run(List<String> command, File workingDir, Duration timeout) {
            commands.add(List.copyOf(command));
            if (command.contains("rev-parse")) {
                return new ProcessResult(0, branch + "\n", "", false);
            }
            if (command.contains("status")) {
                return new ProcessResult(0, "", "", false);
            }
            if (command.contains("remove")) {
                if (removeWorktrees) {
                    try {
                        Files.delete(Path.of(command.get(command.indexOf("remove") + 1)));
                    }
                    catch (Exception ex) {
                        throw new IllegalStateException(ex);
                    }
                }
                return new ProcessResult(0, "", "", false);
            }
            if (command.contains("prune")) {
                return new ProcessResult(0, "", "", false);
            }
            throw new AssertionError("Unexpected command: " + command);
        }
    }

    private record EnqueueCall(
            String specId, String title, String lane, int priority, String actor) {
    }

    private record AnnotationCall(
            String taskId,
            String actor,
            String kind,
            String text,
            Map<String, Object> dataJson) {
    }
}
