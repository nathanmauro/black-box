package dev.nathan.sbaagentic.runner.ship;

import java.io.File;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nathan.sbaagentic.runner.internal.client.blackbox.BlackBoxApiClient;
import dev.nathan.sbaagentic.runner.RepoConfig;
import dev.nathan.sbaagentic.runner.RunnerNaming;
import dev.nathan.sbaagentic.runner.process.ProcessRunner;
import dev.nathan.sbaagentic.runner.process.ProcessRunner.ProcessResult;
import dev.nathan.sbaagentic.runner.process.TmuxController;
import dev.nathan.sbaagentic.runner.ship.ShipExecutor.ShipResult;
import dev.nathan.sbaagentic.runner.internal.client.blackbox.TaskAnnotation;
import dev.nathan.sbaagentic.runner.internal.client.blackbox.TaskChange;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ShipExecutorTest {

    private static final File REPO = new File("/tmp/ship-executor-repo");
    private static final File WORKTREE = new File("/tmp/ship-executor-worktree");
    private static final RepoConfig REPO_CONFIG =
            new RepoConfig(REPO.getAbsolutePath(), true, true, "mvn test", "");

    @Test
    void parsesMergedResultFromLastStdoutLine() {
        RecordingApiClient apiClient = new RecordingApiClient();
        FakeProcessRunner processRunner = new FakeProcessRunner(jsonResult(
                "merged", "checks green", "https://example.test/pr/1", "merged", List.of()));
        ShipExecutor executor = executor(apiClient, processRunner, new FakeTmuxController(false));

        ShipResult result = ship(executor);

        assertThat(result).isEqualTo(new ShipResult(
                "merged",
                "checks green",
                "https://example.test/pr/1",
                "merged",
                List.of()));
        assertThat(processRunner.commands.getFirst())
                .containsExactly(
                        RunnerNaming.scriptPath("scripts/runner/ship.sh"),
                        "task-1",
                        REPO.getAbsolutePath(),
                        "auto/story-task1",
                        WORKTREE.getAbsolutePath(),
                        "true",
                        "true",
                        "",
                        "Story title",
                        "Worker summary");
        assertThat(processRunner.workingDirs.getFirst()).isEqualTo(REPO);
        assertThat(processRunner.timeouts.getFirst()).isEqualTo(Duration.ofMinutes(35));
        assertThat(apiClient.annotations).singleElement().satisfies(annotation ->
                assertThat(annotation.dataJson()).isNull());
    }

    @Test
    void parsesPrOpenResult() {
        RecordingApiClient apiClient = new RecordingApiClient();
        ShipExecutor executor = executor(
                apiClient,
                new FakeProcessRunner(jsonResult(
                        "pr-open",
                        "auto merge disabled",
                        "https://example.test/pr/2",
                        null,
                        List.of("gh pr merge https://example.test/pr/2 --squash"))),
                new FakeTmuxController(false));

        ShipResult result = ship(executor);

        assertThat(result.status()).isEqualTo("pr-open");
        assertThat(result.prUrl()).isEqualTo("https://example.test/pr/2");
        assertThat(result.manualCommands()).containsExactly(
                "gh pr merge https://example.test/pr/2 --squash");
    }

    @Test
    void parsesLocalOnlyResult() {
        RecordingApiClient apiClient = new RecordingApiClient();
        ShipExecutor executor = executor(
                apiClient,
                new FakeProcessRunner(jsonResult(
                        "local-only",
                        "repo config push is not true",
                        null,
                        null,
                        List.of("git push", "gh pr create"))),
                new FakeTmuxController(false));

        ShipResult result = ship(executor);

        assertThat(result.status()).isEqualTo("local-only");
        assertThat(result.reason()).isEqualTo("repo config push is not true");
        assertThat(result.manualCommands()).containsExactly("git push", "gh pr create");
    }

    @Test
    void leavesBlockedAfterOneRepairWaitWithoutANewCommit() {
        RecordingApiClient apiClient = new RecordingApiClient();
        FakeProcessRunner processRunner = new FakeProcessRunner(jsonResult(
                "blocked",
                "checks red: unit-tests",
                "https://example.test/pr/3",
                "checks-red",
                List.of()));
        FakeTmuxController tmux = new FakeTmuxController(true);
        ShipExecutor executor = new ShipExecutor(
                apiClient,
                processRunner,
                tmux,
                new ObjectMapper(),
                Duration.ofMillis(5),
                Duration.ofMillis(1));

        ShipResult result = ship(executor);

        assertThat(result.status()).isEqualTo("blocked");
        assertThat(processRunner.shipInvocations).isEqualTo(1);
        assertThat(tmux.sentText)
                .contains("The PR checks failed:\nchecks red: unit-tests")
                .contains("Please fix and I will re-run verify + push.");
        assertThat(apiClient.completeCalls).isZero();
        assertThat(apiClient.statusCalls).isZero();
    }

    @Test
    void sdlcShipRecordsStructuralResultWithoutRepairing() {
        RecordingApiClient apiClient = new RecordingApiClient();
        FakeProcessRunner processRunner = new FakeProcessRunner(jsonResult(
                "blocked",
                "checks red: unit-tests",
                "https://example.test/pr/4",
                "checks-red",
                List.of()));
        FakeTmuxController tmux = new FakeTmuxController(true);
        ShipExecutor executor = executor(apiClient, processRunner, tmux);

        ShipResult result = executor.shipForSdlc(
                "review-task-1",
                "runner-1",
                REPO_CONFIG,
                "auto/story-task1",
                WORKTREE,
                "Story title",
                "Review summary",
                "approval-1");

        assertThat(result.status()).isEqualTo("blocked");
        assertThat(processRunner.shipInvocations).isEqualTo(1);
        assertThat(processRunner.commands).hasSize(1);
        assertThat(tmux.sentText).isNull();
        assertThat(apiClient.annotations).singleElement().satisfies(annotation -> {
            assertThat(annotation.taskId()).isEqualTo("review-task-1");
            assertThat(annotation.kind()).isEqualTo("progress");
            assertThat(annotation.dataJson())
                    .containsEntry("sdlc", "shipped")
                    .containsEntry("approvalId", "approval-1")
                    .containsEntry("status", "blocked")
                    .containsEntry("branch", "auto/story-task1")
                    .containsEntry("worktree", WORKTREE.getAbsolutePath());
        });
    }

    @Test
    void unparseableOutputDegradesToLocalOnly() {
        RecordingApiClient apiClient = new RecordingApiClient();
        FakeProcessRunner processRunner = new FakeProcessRunner(
                new ProcessResult(1, "progress\nnot-json\n", "shell failed", false));
        ShipExecutor executor = executor(apiClient, processRunner, new FakeTmuxController(false));

        ShipResult result = ship(executor);

        assertThat(result.status()).isEqualTo("local-only");
        assertThat(result.reason())
                .startsWith("ship.sh produced unparseable output: ")
                .contains("not-json")
                .contains("shell failed");
    }

    private static ShipExecutor executor(
            BlackBoxApiClient apiClient,
            ProcessRunner processRunner,
            TmuxController tmux) {
        return new ShipExecutor(
                apiClient,
                processRunner,
                tmux,
                new ObjectMapper(),
                Duration.ofMillis(5),
                Duration.ofMillis(1));
    }

    private static ShipResult ship(ShipExecutor executor) {
        return executor.ship(
                "task-1",
                "runner-1",
                REPO_CONFIG,
                "auto/story-task1",
                WORKTREE,
                "Story title",
                "Worker summary",
                "bb-run-task1");
    }

    private static ProcessResult jsonResult(
            String status,
            String reason,
            String prUrl,
            String mergeStatus,
            List<String> manualCommands) {
        try {
            String json = new ObjectMapper().writeValueAsString(
                    new ShipResult(status, reason, prUrl, mergeStatus, manualCommands));
            return new ProcessResult(0, "diagnostic stdout is ignored\n" + json + "\n", "", false);
        }
        catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static final class FakeProcessRunner implements ProcessRunner {

        private final ProcessResult shipResult;
        private final List<List<String>> commands = new ArrayList<>();
        private final List<File> workingDirs = new ArrayList<>();
        private final List<Duration> timeouts = new ArrayList<>();
        private int shipInvocations;

        private FakeProcessRunner(ProcessResult shipResult) {
            this.shipResult = shipResult;
        }

        @Override
        public ProcessResult run(List<String> command, File workingDir, Duration timeout) {
            commands.add(command);
            workingDirs.add(workingDir);
            timeouts.add(timeout);
            if (!command.isEmpty()
                    && RunnerNaming.scriptPath("scripts/runner/ship.sh").equals(command.getFirst())) {
                shipInvocations++;
                return shipResult;
            }
            if (command.contains("--format=%H")) {
                return new ProcessResult(0, "abc123\n", "", false);
            }
            throw new AssertionError("Unexpected command: " + command);
        }
    }

    private static final class RecordingApiClient extends BlackBoxApiClient {

        private int completeCalls;
        private int statusCalls;
        private final List<AnnotationCall> annotations = new ArrayList<>();

        private RecordingApiClient() {
            super(new ObjectMapper());
        }

        @Override
        public TaskAnnotation annotate(
                String taskId,
                String actor,
                String kind,
                String text,
                Map<String, Object> dataJson) {
            annotations.add(new AnnotationCall(taskId, actor, kind, text, dataJson));
            return null;
        }

        @Override
        public TaskChange completeTask(
                String taskId,
                String actor,
                String source,
                String clientSessionId,
                String summary,
                List<String> openLoops,
                String nextAction) {
            completeCalls++;
            return null;
        }

        @Override
        public TaskChange updateTaskStatus(
                String taskId, String actor, String status, String blockedReason) {
            statusCalls++;
            return null;
        }
    }

    private record AnnotationCall(
            String taskId,
            String actor,
            String kind,
            String text,
            Map<String, Object> dataJson) {
    }

    private static final class FakeTmuxController implements TmuxController {

        private final boolean sessionExists;
        private String sentText;

        private FakeTmuxController(boolean sessionExists) {
            this.sessionExists = sessionExists;
        }

        @Override
        public boolean hasSession(String sessionName) {
            return sessionExists;
        }

        @Override
        public void killSession(String sessionName) {
        }

        @Override
        public void newSession(String sessionName, File cwd, int width, int height) {
        }

        @Override
        public void sendKeys(String sessionName, String text) {
            sentText = text;
        }

        @Override
        public String capturePane(String sessionName) {
            return "";
        }
    }
}
