package dev.nathan.sbaagentic.runner.run;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import dev.nathan.sbaagentic.runner.EngineConfig;
import dev.nathan.sbaagentic.runner.RepoConfig;
import dev.nathan.sbaagentic.runner.RunExecutor;
import dev.nathan.sbaagentic.runner.RunnerConfig;
import dev.nathan.sbaagentic.runner.RunnerNaming;
import dev.nathan.sbaagentic.runner.engine.FakeEngine;
import dev.nathan.sbaagentic.runner.gate.StoryFrontmatterParser;
import dev.nathan.sbaagentic.runner.process.ProcessRunner.ProcessResult;
import dev.nathan.sbaagentic.runner.process.RealProcessRunner;
import dev.nathan.sbaagentic.runner.process.TmuxController;
import dev.nathan.sbaagentic.task.Task;
import dev.nathan.sbaagentic.task.TaskChange;
import dev.nathan.sbaagentic.task.TaskEvent;
import dev.nathan.sbaagentic.task.TaskEventType;
import dev.nathan.sbaagentic.task.TaskSnapshot;
import dev.nathan.sbaagentic.task.TaskSpec;
import dev.nathan.sbaagentic.task.TaskStatus;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
class RunExecutorIntegrationTest {

    private static final String TASK_ID = "12345678-abcd-4abc-8abc-1234567890ab";

    @TempDir
    Path tempDir;

    @Test
    void createsNamedWorktreeRunsFakeEngineAndInterimCompletes() throws Exception {
        RealProcessRunner processRunner = new RealProcessRunner();
        Path repo = initializeRepo(processRunner);
        FakeBlackBoxApiClient apiClient = new FakeBlackBoxApiClient();
        apiClient.taskEventsSupplier = () -> List.of(workerDoneEvent());
        TestTmuxController tmux = new TestTmuxController(processRunner);
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

    private static TaskChange taskChange(Path repo) {
        Task task = new Task(
                TASK_ID,
                "spec-1",
                repo.toString(),
                "Implement worker's result",
                "auto",
                TaskStatus.IN_PROGRESS,
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
                + "mode: full_auto\n"
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

    private static TaskEvent workerDoneEvent() {
        return new TaskEvent(
                "event-1",
                TASK_ID,
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
        private boolean sessionExists;
        private Path cwd;

        private TestTmuxController(RealProcessRunner processRunner) {
            this.processRunner = processRunner;
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
            try {
                Files.writeString(cwd.resolve(".blackbox-fake-worker.log"), "fake worker\n");
            }
            catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
            assertSuccess(run(processRunner, cwd, "git", "add", ".blackbox-fake-worker.log"));
            assertSuccess(run(
                    processRunner, cwd, "git", "commit", "-m", "fake worker test commit"));
        }

        @Override
        public String capturePane(String sessionName) {
            return "";
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
