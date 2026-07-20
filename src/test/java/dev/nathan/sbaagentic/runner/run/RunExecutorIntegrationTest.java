package dev.nathan.sbaagentic.runner.run;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import dev.nathan.sbaagentic.runner.EngineConfig;
import dev.nathan.sbaagentic.runner.RepoConfig;
import dev.nathan.sbaagentic.runner.RunExecutor;
import dev.nathan.sbaagentic.runner.RunnerConfig;
import dev.nathan.sbaagentic.runner.RunnerNaming;
import dev.nathan.sbaagentic.runner.SdlcPlanCycle;
import dev.nathan.sbaagentic.runner.SdlcReviewCycle;
import dev.nathan.sbaagentic.runner.engine.FakeEngine;
import dev.nathan.sbaagentic.runner.gate.StoryFrontmatterParser;
import dev.nathan.sbaagentic.runner.process.ProcessRunner.ProcessResult;
import dev.nathan.sbaagentic.runner.process.RealProcessRunner;
import dev.nathan.sbaagentic.runner.process.TmuxController;
import dev.nathan.sbaagentic.runner.ship.ShipExecutor;
import dev.nathan.sbaagentic.workflow.Task;
import dev.nathan.sbaagentic.workflow.TaskChange;
import dev.nathan.sbaagentic.workflow.TaskEvent;
import dev.nathan.sbaagentic.workflow.TaskEventType;
import dev.nathan.sbaagentic.workflow.TaskSnapshot;
import dev.nathan.sbaagentic.workflow.TaskSpec;
import dev.nathan.sbaagentic.workflow.TaskStatus;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class RunExecutorIntegrationTest {

    private static final String TASK_ID = "12345678-abcd-4abc-8abc-1234567890ab";
    private static final String PLAN_TASK_ID = "22345678-abcd-4abc-8abc-1234567890ab";
    private static final String REVIEW_TASK_ID = "32345678-abcd-4abc-8abc-1234567890ab";
    private static final String PLAN_TEXT = "## Plan\n\n1. Implement the requested behavior.\n2. Run verification.";
    private static final String REVIEW_TEXT = "## Review\n\nNo blocking findings; verification is green.";

    @TempDir
    Path tempDir;

    @Test
    void createsNamedWorktreeRunsFakeEngineAndInterimCompletes() throws Exception {
        RealProcessRunner processRunner = new RealProcessRunner();
        Path repo = initializeRepo(processRunner);
        FakeBlackBoxApiClient apiClient = new FakeBlackBoxApiClient();
        TestTmuxController tmux = new TestTmuxController(processRunner, apiClient);
        CompletionDetector completionDetector = new CompletionDetector(apiClient, tmux, processRunner);
        RecordingWorkerSessionIngest workerSessionIngest = new RecordingWorkerSessionIngest(apiClient);
        ActiveRunRegistry activeRunRegistry = new ActiveRunRegistry();
        RunExecutor executor = new RunExecutor(
                apiClient,
                tmux,
                processRunner,
                completionDetector,
                workerSessionIngest,
                new GoalPromptBuilder(),
                new StoryFrontmatterParser(),
                List.of(new FakeEngine()),
                activeRunRegistry);
        RunnerConfig config = new RunnerConfig(
                1,
                List.of(new EngineConfig("fake", null, null, null, null, true)),
                null,
                List.of(new RepoConfig(repo.toString(), false, false, "git status --short", "")));

        executor.execute(taskChange(repo), config, "blackbox-runner", "orchestrator-1");

        Path worktree = repo.resolve(RunnerNaming.worktreeDirName(TASK_ID));
        assertThat(worktree).isDirectory();
        assertThat(run(processRunner, worktree, "git", "branch", "--show-current").stdout().strip())
                .isEqualTo("auto/implement-worker-s-result-12345678");
        assertThat(run(processRunner, worktree, "git", "log", "-1", "--oneline").stdout())
                .contains("fake worker test commit");
        assertThat(tmux.sessionExists).isFalse();
        assertThat(activeRunRegistry.tmuxSessionFor(TASK_ID)).isEmpty();
        assertThat(workerSessionIngest.call).isEqualTo(new IngestCall(
                worktree.toFile(), TASK_ID, "blackbox-runner", "orchestrator-1"));
        assertThat(apiClient.completeCalls).singleElement().satisfies(call -> {
            assertThat(call.taskId()).isEqualTo(TASK_ID);
            assertThat(call.actor()).isEqualTo("blackbox-runner");
            assertThat(call.source()).isEqualTo("cli");
            assertThat(call.clientSessionId()).isEqualTo("blackbox-runner-run-" + TASK_ID);
            assertThat(call.summary())
                    .isEqualTo("Work committed locally only: repo config push is not true");
            assertThat(call.nextAction()).isEqualTo("Run the manual commands to push/PR/merge, "
                    + "or re-run the runner once the gate is fixed.");
        });
    }

    @Test
    void sdlcBuildPreservesArtifactEnqueuesReviewAndDefersShip() throws Exception {
        RealProcessRunner processRunner = new RealProcessRunner();
        Path repo = initializeRepo(processRunner);
        FakeBlackBoxApiClient apiClient = new FakeBlackBoxApiClient();
        apiClient.taskSnapshots = List.of(
                taskChange(repo, PLAN_TASK_ID, "sdlc:plan", "sdlc", TaskStatus.DONE).snapshot());
        apiClient.setTaskEvents(
                PLAN_TASK_ID,
                List.of(
                        annotationEvent(PLAN_TASK_ID, "plan", PLAN_TEXT, null),
                        approvalEvent(PLAN_TASK_ID, "approve", "plan")));
        TestTmuxController tmux = new TestTmuxController(processRunner, apiClient);
        ShipExecutor shipExecutor = mock(ShipExecutor.class);
        RunExecutor executor = executor(
                apiClient, tmux, processRunner, new GoalPromptBuilder(), shipExecutor);

        executor.execute(
                taskChange(repo, TASK_ID, "auto", "sdlc", TaskStatus.IN_PROGRESS),
                config(repo),
                "blackbox-runner",
                "orchestrator-build");

        Path worktree = repo.resolve(RunnerNaming.worktreeDirName(TASK_ID));
        String branch = expectedBranch(TASK_ID);
        assertThat(worktree).isDirectory();
        assertThat(run(processRunner, worktree, "git", "branch", "--show-current").stdout().strip())
                .isEqualTo(branch);
        assertThat(run(processRunner, worktree, "git", "log", "-1", "--oneline").stdout())
                .contains("fake worker test commit");
        assertThat(apiClient.annotationCalls).contains(
                new FakeBlackBoxApiClient.AnnotationCall(
                        TASK_ID,
                        "blackbox-runner",
                        "progress",
                        "SDLC build verified and committed; shipping is deferred until review approval.",
                        Map.of(
                                "branch", branch,
                                "worktree", worktree.toAbsolutePath().toString())));
        assertThat(apiClient.enqueueCalls).containsExactly(
                new FakeBlackBoxApiClient.EnqueueCall(
                        "spec-1",
                        "Implement worker's result",
                        "sdlc:review",
                        10,
                        "blackbox-runner"));
        verifyNoInteractions(shipExecutor);
    }

    @Test
    void sdlcBuildWithoutApprovedPlanFailsClosedBeforeCreatingWorktree() throws Exception {
        RealProcessRunner processRunner = new RealProcessRunner();
        Path repo = initializeRepo(processRunner);
        FakeBlackBoxApiClient apiClient = new FakeBlackBoxApiClient();
        TestTmuxController tmux = new TestTmuxController(processRunner, apiClient);
        ShipExecutor shipExecutor = mock(ShipExecutor.class);
        RunExecutor executor = executor(
                apiClient, tmux, processRunner, new GoalPromptBuilder(), shipExecutor);

        executor.execute(
                taskChange(repo, TASK_ID, "auto", "sdlc", TaskStatus.IN_PROGRESS),
                config(repo),
                "blackbox-runner",
                "orchestrator-build");

        assertThat(apiClient.statusCalls).containsExactly(
                new FakeBlackBoxApiClient.StatusCall(
                        TASK_ID,
                        "blackbox-runner",
                        "blocked",
                        "SDLC build requires a completed plan and an un-rejected plan approval."));
        assertThat(repo.resolve(RunnerNaming.worktreeDirName(TASK_ID))).doesNotExist();
        assertThat(apiClient.enqueueCalls).isEmpty();
        verifyNoInteractions(shipExecutor);
    }

    @Test
    void sdlcPlanPostsAndConsumesPlanWithoutCommitThenCleansWorktree() throws Exception {
        RealProcessRunner processRunner = new RealProcessRunner();
        Path repo = initializeRepo(processRunner);
        String originalHead = gitHead(processRunner, repo);
        FakeBlackBoxApiClient apiClient = new FakeBlackBoxApiClient();
        TestTmuxController tmux = new TestTmuxController(processRunner, apiClient);
        ShipExecutor shipExecutor = mock(ShipExecutor.class);
        RunExecutor executor = executor(
                apiClient, tmux, processRunner, new GoalPromptBuilder(), shipExecutor);

        new SdlcPlanCycle(executor).execute(
                taskChange(repo, PLAN_TASK_ID, "sdlc:plan", "sdlc", TaskStatus.IN_PROGRESS),
                config(repo),
                "blackbox-runner",
                "orchestrator-plan");

        Path worktree = repo.resolve(RunnerNaming.worktreeDirName(PLAN_TASK_ID));
        assertThat(apiClient.annotationCalls).contains(
                new FakeBlackBoxApiClient.AnnotationCall(
                        PLAN_TASK_ID,
                        "blackbox-runner-worker",
                        "plan",
                        PLAN_TEXT,
                        null));
        assertThat(apiClient.completeCalls).singleElement().satisfies(call -> {
            assertThat(call.taskId()).isEqualTo(PLAN_TASK_ID);
            assertThat(call.summary()).isEqualTo(PLAN_TEXT);
            assertThat(call.nextAction()).isEqualTo("Await human approval of the SDLC plan.");
        });
        assertThat(gitHead(processRunner, repo)).isEqualTo(originalHead);
        assertThat(worktree).doesNotExist();
        assertThat(run(
                        processRunner,
                        repo,
                        "git",
                        "branch",
                        "--list",
                        expectedBranch(PLAN_TASK_ID))
                .stdout()).isBlank();
        verifyNoInteractions(shipExecutor);
    }

    @Test
    void sdlcReviewUsesApprovedPlanAndPreservedBuildWithoutCommitOrCleanup() throws Exception {
        RealProcessRunner processRunner = new RealProcessRunner();
        Path repo = initializeRepo(processRunner);
        Path buildWorktree = createPreservedBuild(processRunner, repo, TASK_ID);
        String buildBranch = expectedBranch(TASK_ID);
        String buildHead = gitHead(processRunner, buildWorktree);
        FakeBlackBoxApiClient apiClient = new FakeBlackBoxApiClient();
        apiClient.taskSnapshots = List.of(
                taskChange(repo, TASK_ID, "auto", "sdlc", TaskStatus.DONE).snapshot(),
                taskChange(repo, PLAN_TASK_ID, "sdlc:plan", "sdlc", TaskStatus.DONE).snapshot());
        apiClient.setTaskEvents(
                TASK_ID,
                List.of(annotationEvent(
                        TASK_ID,
                        "blackbox-runner",
                        "progress",
                        "SDLC build artifact",
                        Map.of(
                                "branch", buildBranch,
                                "worktree", buildWorktree.toAbsolutePath().toString()))));
        apiClient.setTaskEvents(
                PLAN_TASK_ID,
                List.of(
                        annotationEvent(PLAN_TASK_ID, "plan", PLAN_TEXT, null),
                        approvalEvent(PLAN_TASK_ID, "approve", "plan")));
        TestTmuxController tmux = new TestTmuxController(processRunner, apiClient);
        RecordingGoalPromptBuilder promptBuilder = new RecordingGoalPromptBuilder();
        ShipExecutor shipExecutor = mock(ShipExecutor.class);
        RunExecutor executor = executor(apiClient, tmux, processRunner, promptBuilder, shipExecutor);

        new SdlcReviewCycle(executor).execute(
                taskChange(repo, REVIEW_TASK_ID, "sdlc:review", "sdlc", TaskStatus.IN_PROGRESS),
                config(repo),
                "blackbox-runner",
                "orchestrator-review");

        assertThat(tmux.cwd).isEqualTo(buildWorktree);
        assertThat(promptBuilder.approvedPlan).isEqualTo(PLAN_TEXT);
        assertThat(apiClient.annotationCalls).contains(
                new FakeBlackBoxApiClient.AnnotationCall(
                        REVIEW_TASK_ID,
                        "blackbox-runner-worker",
                        "review",
                        REVIEW_TEXT,
                        null));
        assertThat(apiClient.completeCalls).singleElement().satisfies(call -> {
            assertThat(call.taskId()).isEqualTo(REVIEW_TASK_ID);
            assertThat(call.summary()).isEqualTo(REVIEW_TEXT);
            assertThat(call.nextAction()).isEqualTo("Await human approval of the SDLC review.");
        });
        assertThat(gitHead(processRunner, buildWorktree)).isEqualTo(buildHead);
        assertThat(run(processRunner, buildWorktree, "git", "status", "--porcelain").stdout())
                .isBlank();
        assertThat(buildWorktree).isDirectory();
        assertThat(run(processRunner, buildWorktree, "git", "branch", "--show-current").stdout().strip())
                .isEqualTo(buildBranch);
        verifyNoInteractions(shipExecutor);
    }

    private static RunExecutor executor(
            FakeBlackBoxApiClient apiClient,
            TestTmuxController tmux,
            RealProcessRunner processRunner,
            GoalPromptBuilder promptBuilder,
            ShipExecutor shipExecutor) {
        return new RunExecutor(
                apiClient,
                tmux,
                processRunner,
                new CompletionDetector(apiClient, tmux, processRunner),
                new RecordingWorkerSessionIngest(apiClient),
                promptBuilder,
                new StoryFrontmatterParser(),
                List.of(new FakeEngine()),
                new ActiveRunRegistry(),
                shipExecutor);
    }

    private static RunnerConfig config(Path repo) {
        return new RunnerConfig(
                1,
                List.of(new EngineConfig("fake", null, null, null, null, true)),
                null,
                List.of(new RepoConfig(repo.toString(), false, false, "git status --short", "")));
    }

    private Path initializeRepo(RealProcessRunner processRunner) throws Exception {
        Path repo = Files.createDirectories(tempDir.resolve("repo with spaces"));
        assertSuccess(run(processRunner, repo, "git", "init"));
        assertSuccess(run(processRunner, repo, "git", "config", "user.name", "Nathan"));
        assertSuccess(run(processRunner, repo, "git", "config", "user.email", "nathan@example.test"));
        Files.writeString(repo.resolve("README.md"), "fixture\n");
        assertSuccess(run(processRunner, repo, "git", "add", "README.md"));
        assertSuccess(run(processRunner, repo, "git", "commit", "-m", "initial fixture"));
        return repo;
    }

    private static Path createPreservedBuild(
            RealProcessRunner processRunner, Path repo, String taskId) throws Exception {
        Path worktree = repo.resolve(RunnerNaming.worktreeDirName(taskId));
        Files.createDirectories(worktree.getParent());
        assertSuccess(run(
                processRunner,
                repo,
                "git",
                "worktree",
                "add",
                worktree.toString(),
                "-b",
                expectedBranch(taskId),
                "HEAD"));
        Files.writeString(worktree.resolve(".blackbox-fake-worker.log"), "fake worker\n");
        assertSuccess(run(
                processRunner,
                worktree,
                "git",
                "add",
                ".blackbox-fake-worker.log"));
        assertSuccess(run(
                processRunner,
                worktree,
                "git",
                "commit",
                "-m",
                "fake worker test commit"));
        return worktree;
    }

    private static String gitHead(RealProcessRunner processRunner, Path repo) {
        ProcessResult result = run(processRunner, repo, "git", "rev-parse", "HEAD");
        assertSuccess(result);
        return result.stdout().strip();
    }

    private static String expectedBranch(String taskId) {
        return "auto/implement-worker-s-result-" + RunnerNaming.taskShort(taskId);
    }

    private static TaskChange taskChange(Path repo) {
        return taskChange(repo, TASK_ID, "auto", "full_auto", TaskStatus.IN_PROGRESS);
    }

    private static TaskChange taskChange(
            Path repo,
            String taskId,
            String lane,
            String mode,
            TaskStatus status) {
        Task task = new Task(
                taskId,
                "spec-1",
                repo.toString(),
                "Implement worker's result",
                lane,
                status,
                10,
                "test",
                "blackbox-runner",
                null,
                null,
                Instant.now(),
                Instant.now());
        String body = "---\n"
                + "story: v1\n"
                + "repo: '" + repo.toString().replace("'", "''") + "'\n"
                + "mode: " + mode + "\n"
                + "verify: 'git status --short'\n"
                + "push: false\n"
                + "priority: 10\n"
                + "---\n"
                + "# Worker story\n\n"
                + "## Acceptance criteria\n- A marker commit exists.\n";
        TaskSpec spec = new TaskSpec(
                "spec-1", repo.toString(), "Worker story", body, null, null, "test", null, null);
        return new TaskChange(new TaskSnapshot(task, spec), null);
    }

    private static TaskEvent workerDoneEvent(String taskId) {
        return new TaskEvent(
                "event-done-" + taskId,
                taskId,
                TaskEventType.NOTE,
                "blackbox-runner-worker",
                null,
                null,
                Map.of(
                        "kind", "progress",
                        "text", "fake worker done",
                        "dataJson", Map.of("event", "worker_done", "outcome", "done")),
                Instant.now());
    }

    private static TaskEvent annotationEvent(
            String taskId,
            String kind,
            String text,
            Map<String, Object> dataJson) {
        return annotationEvent(taskId, "blackbox-runner-worker", kind, text, dataJson);
    }

    private static TaskEvent annotationEvent(
            String taskId,
            String actor,
            String kind,
            String text,
            Map<String, Object> dataJson) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("kind", kind);
        detail.put("text", text);
        if (dataJson != null) {
            detail.put("dataJson", dataJson);
        }
        return new TaskEvent(
                "event-" + kind + "-" + taskId,
                taskId,
                TaskEventType.NOTE,
                actor,
                null,
                null,
                detail,
                Instant.now());
    }

    private static TaskEvent approvalEvent(String taskId, String decision, String stage) {
        return annotationEvent(
                taskId,
                "nathan",
                "approval",
                decision,
                Map.of("decision", decision, "stage", stage, "feedback", ""));
    }

    private static ProcessResult run(
            RealProcessRunner processRunner, Path cwd, String... command) {
        return processRunner.run(List.of(command), cwd.toFile(), Duration.ofSeconds(10));
    }

    private static void assertSuccess(ProcessResult result) {
        assertThat(result.exitCode())
                .as("command stderr: %s", result.stderr())
                .isZero();
    }

    private static final class TestTmuxController implements TmuxController {

        private final RealProcessRunner processRunner;
        private final FakeBlackBoxApiClient apiClient;
        private boolean sessionExists;
        private Path cwd;

        private TestTmuxController(
                RealProcessRunner processRunner, FakeBlackBoxApiClient apiClient) {
            this.processRunner = processRunner;
            this.apiClient = apiClient;
        }

        @Override
        public boolean hasSession(String sessionName) {
            return sessionExists;
        }

        @Override
        public void killSession(String sessionName) {
            sessionExists = false;
        }

        @Override
        public void newSession(String sessionName, File cwd, int width, int height) {
            sessionExists = true;
            this.cwd = cwd.toPath();
        }

        @Override
        public void sendKeys(String sessionName, String text) {
            String taskId = exportedValue(text, "SBA_TASK_ID");
            if (text.contains("SBA_STAGE='plan'")) {
                apiClient.annotate(
                        taskId,
                        "blackbox-runner-worker",
                        "plan",
                        PLAN_TEXT,
                        null);
                apiClient.setTaskEvents(
                        taskId,
                        List.of(
                                annotationEvent(taskId, "plan", PLAN_TEXT, null),
                                workerDoneEvent(taskId)));
                return;
            }
            if (text.contains("SBA_STAGE='review'")) {
                apiClient.annotate(
                        taskId,
                        "blackbox-runner-worker",
                        "review",
                        REVIEW_TEXT,
                        null);
                apiClient.setTaskEvents(
                        taskId,
                        List.of(
                                annotationEvent(taskId, "review", REVIEW_TEXT, null),
                                workerDoneEvent(taskId)));
                return;
            }
            try {
                Files.writeString(cwd.resolve(".blackbox-fake-worker.log"), "fake worker\n");
            }
            catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
            assertSuccess(run(processRunner, cwd, "git", "add", ".blackbox-fake-worker.log"));
            assertSuccess(run(
                    processRunner, cwd, "git", "commit", "-m", "fake worker test commit"));
            apiClient.setTaskEvents(taskId, List.of(workerDoneEvent(taskId)));
        }

        @Override
        public String capturePane(String sessionName) {
            return "";
        }

        private static String exportedValue(String command, String name) {
            String prefix = "export " + name + "='";
            int start = command.indexOf(prefix);
            if (start < 0) {
                throw new IllegalStateException("Missing " + name + " export in command: " + command);
            }
            start += prefix.length();
            int end = command.indexOf('\'', start);
            if (end < 0) {
                throw new IllegalStateException("Unterminated " + name + " export in command: " + command);
            }
            return command.substring(start, end);
        }
    }

    private static final class RecordingGoalPromptBuilder extends GoalPromptBuilder {

        private String approvedPlan;

        @Override
        public String buildReview(
                String taskId,
                String storyBody,
                String resolvedVerify,
                String approvedPlan) {
            this.approvedPlan = approvedPlan;
            return super.buildReview(taskId, storyBody, resolvedVerify, approvedPlan);
        }
    }

    private static final class RecordingWorkerSessionIngest extends WorkerSessionIngest {

        private IngestCall call;

        private RecordingWorkerSessionIngest(FakeBlackBoxApiClient apiClient) {
            super(apiClient, new com.fasterxml.jackson.databind.ObjectMapper());
        }

        @Override
        public Optional<String> ingestAndLink(
                File worktreeDir,
                String taskId,
                String actorId,
                String orchestratorSessionId) {
            call = new IngestCall(worktreeDir, taskId, actorId, orchestratorSessionId);
            return Optional.empty();
        }
    }

    private record IngestCall(
            File worktreeDir,
            String taskId,
            String actorId,
            String orchestratorSessionId) {
    }
}
