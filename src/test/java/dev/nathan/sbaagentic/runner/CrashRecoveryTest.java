package dev.nathan.sbaagentic.runner;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nathan.sbaagentic.runner.gate.StoryFrontmatterParser;
import dev.nathan.sbaagentic.runner.internal.client.blackbox.BlackBoxApiClient;
import dev.nathan.sbaagentic.runner.internal.client.blackbox.SpecStatus;
import dev.nathan.sbaagentic.runner.internal.client.blackbox.Task;
import dev.nathan.sbaagentic.runner.internal.client.blackbox.TaskAnnotation;
import dev.nathan.sbaagentic.runner.internal.client.blackbox.TaskChange;
import dev.nathan.sbaagentic.runner.internal.client.blackbox.TaskEvent;
import dev.nathan.sbaagentic.runner.internal.client.blackbox.TaskEventType;
import dev.nathan.sbaagentic.runner.internal.client.blackbox.TaskSnapshot;
import dev.nathan.sbaagentic.runner.internal.client.blackbox.TaskSpec;
import dev.nathan.sbaagentic.runner.internal.client.blackbox.TaskStatus;
import dev.nathan.sbaagentic.runner.process.ProcessRunner;
import dev.nathan.sbaagentic.runner.process.TmuxController;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CrashRecoveryTest {

    @TempDir
    Path tempDir;

    @Test
    void preservesCleanBuildWorktreeWhileSdlcReviewChainIsDone() throws Exception {
        Path repo = Files.createDirectories(tempDir.resolve("repo"));
        Path worktree = Files.createDirectories(repo.resolve(".worktrees/bb-build-1"));
        TaskSnapshot build = snapshot(repo, "build-1", "auto", TaskStatus.DONE);
        TaskSnapshot review = snapshot(repo, "review-1", "sdlc:review", TaskStatus.DONE);
        RecordingApiClient apiClient = new RecordingApiClient(build, review);
        ProcessRunner processRunner = (command, workingDir, timeout) -> {
            throw new AssertionError("Protected worktree should not be inspected or pruned: " + command);
        };
        CrashRecovery recovery =
                new CrashRecovery(apiClient, new NoOpTmux(), processRunner, new StoryFrontmatterParser());

        recovery.reconcile(
                new RunnerConfig(
                        1,
                        List.of(),
                        null,
                        List.of(new RepoConfig(repo.toString(), false, false, "git status --short", ""))),
                "blackbox-runner");

        assertThat(worktree).isDirectory();
    }

    @Test
    void preservesDeferredBuildWhenCompletionCrashesBeforeReviewSuccessorExists() throws Exception {
        Path repo = Files.createDirectories(tempDir.resolve("repo"));
        Path worktree = Files.createDirectories(repo.resolve(".worktrees/bb-build-1"));
        TaskSnapshot build = snapshot(repo, "build-1", "auto", TaskStatus.IN_PROGRESS);
        TaskEvent buildState = new TaskEvent(
                "build-state",
                "build-1",
                TaskEventType.NOTE,
                "blackbox-runner",
                null,
                null,
                Map.of(
                        "kind", "progress",
                        "text", "Build preserved for review.",
                        "dataJson",
                                Map.of(
                                        "branch", RunExecutor.branchName("Story task", "build-1"),
                                        "worktree", worktree.toString())),
                Instant.parse("2026-07-16T12:00:01Z"));
        TaskEvent workerDone = new TaskEvent(
                "worker-done",
                "build-1",
                TaskEventType.NOTE,
                "blackbox-runner-worker",
                null,
                null,
                Map.of(
                        "kind", "progress",
                        "text", "Worker completed.",
                        "dataJson", Map.of("event", "worker_done", "outcome", "done")),
                Instant.parse("2026-07-16T12:00:00Z"));
        RecordingApiClient apiClient = new RecordingApiClient(build, null, List.of(workerDone, buildState));
        ProcessRunner processRunner = (command, workingDir, timeout) -> {
            throw new AssertionError("Deferred worktree should not be pruned: " + command);
        };
        CrashRecovery recovery =
                new CrashRecovery(apiClient, new NoOpTmux(), processRunner, new StoryFrontmatterParser());

        recovery.reconcile(
                new RunnerConfig(
                        1,
                        List.of(),
                        null,
                        List.of(new RepoConfig(repo.toString(), false, false, "git status --short", ""))),
                "blackbox-runner");

        assertThat(worktree).isDirectory();
    }

    @Test
    void preservesCleanOrphanedWorktreeCarryingCommitsThatWereNeverPublished() throws Exception {
        Path repo = Files.createDirectories(tempDir.resolve("repo"));
        Path worktree = Files.createDirectories(repo.resolve(".worktrees/bb-build-1"));
        TaskSnapshot build = snapshot(repo, "build-1", "auto", TaskStatus.DONE);
        ScriptedProcessRunner processRunner = new ScriptedProcessRunner()
                .on("status", ok(""))
                .on("rev-parse --abbrev-ref HEAD", ok("auto/story-task-build-1"))
                .on("rev-parse --abbrev-ref origin/HEAD", ok("origin/main"))
                .on("rev-list", ok("3"));

        prune(repo, build, processRunner);

        assertThat(worktree).isDirectory();
        assertThat(processRunner.issued("worktree", "remove")).isFalse();
        assertThat(processRunner.issued("branch", "-D")).isFalse();
    }

    @Test
    void prunesCleanOrphanedWorktreeWhoseCommitsAreAllPublished() throws Exception {
        Path repo = Files.createDirectories(tempDir.resolve("repo"));
        Files.createDirectories(repo.resolve(".worktrees/bb-build-1"));
        TaskSnapshot build = snapshot(repo, "build-1", "auto", TaskStatus.DONE);
        ScriptedProcessRunner processRunner = new ScriptedProcessRunner()
                .on("status", ok(""))
                .on("rev-parse --abbrev-ref HEAD", ok("auto/story-task-build-1"))
                .on("rev-parse --abbrev-ref origin/HEAD", ok("origin/main"))
                .on("rev-list", ok("0"))
                .on("worktree remove", ok(""))
                .on("worktree prune", ok(""))
                .on("branch -D", ok(""));

        prune(repo, build, processRunner);

        assertThat(processRunner.issued("worktree", "remove")).isTrue();
        assertThat(processRunner.issued("branch", "-D")).isTrue();
    }

    @Test
    void preservesOrphanedWorktreeWhenTheDefaultBranchCannotBeResolved() throws Exception {
        Path repo = Files.createDirectories(tempDir.resolve("repo"));
        Path worktree = Files.createDirectories(repo.resolve(".worktrees/bb-build-1"));
        TaskSnapshot build = snapshot(repo, "build-1", "auto", TaskStatus.DONE);
        ScriptedProcessRunner processRunner = new ScriptedProcessRunner()
                .on("status", ok(""))
                .on("rev-parse --abbrev-ref HEAD", ok("auto/story-task-build-1"))
                .on("rev-parse --abbrev-ref origin/HEAD", failed())
                .on("symbolic-ref", failed());

        prune(repo, build, processRunner);

        assertThat(worktree).isDirectory();
        assertThat(processRunner.issued("rev-list")).isFalse();
        assertThat(processRunner.issued("worktree", "remove")).isFalse();
        assertThat(processRunner.issued("branch", "-D")).isFalse();
    }

    @Test
    void preservesOrphanedWorktreeWhenTheCommitProbeFails() throws Exception {
        Path repo = Files.createDirectories(tempDir.resolve("repo"));
        Path worktree = Files.createDirectories(repo.resolve(".worktrees/bb-build-1"));
        TaskSnapshot build = snapshot(repo, "build-1", "auto", TaskStatus.DONE);
        ScriptedProcessRunner processRunner = new ScriptedProcessRunner()
                .on("status", ok(""))
                .on("rev-parse --abbrev-ref HEAD", ok("auto/story-task-build-1"))
                .on("rev-parse --abbrev-ref origin/HEAD", ok("origin/main"))
                .on("rev-list", failed());

        prune(repo, build, processRunner);

        assertThat(worktree).isDirectory();
        assertThat(processRunner.issued("worktree", "remove")).isFalse();
        assertThat(processRunner.issued("branch", "-D")).isFalse();
    }

    @Test
    void fallsBackToTheLocalDefaultBranchWhenThereIsNoOrigin() throws Exception {
        Path repo = Files.createDirectories(tempDir.resolve("repo"));
        Path worktree = Files.createDirectories(repo.resolve(".worktrees/bb-build-1"));
        TaskSnapshot build = snapshot(repo, "build-1", "auto", TaskStatus.DONE);
        ScriptedProcessRunner processRunner = new ScriptedProcessRunner()
                .on("status", ok(""))
                .on("rev-parse --abbrev-ref HEAD", ok("auto/story-task-build-1"))
                .on("rev-parse --abbrev-ref origin/HEAD", failed())
                .on("symbolic-ref", ok("main"))
                .on("rev-list", ok("2"));

        prune(repo, build, processRunner);

        assertThat(worktree).isDirectory();
        assertThat(processRunner.commandFor("rev-list")).contains("main");
        assertThat(processRunner.issued("branch", "-D")).isFalse();
    }

    private void prune(Path repo, TaskSnapshot build, ProcessRunner processRunner) {
        CrashRecovery recovery = new CrashRecovery(
                new RecordingApiClient(build, null), new NoOpTmux(), processRunner, new StoryFrontmatterParser());

        recovery.reconcile(
                new RunnerConfig(
                        1,
                        List.of(),
                        null,
                        List.of(new RepoConfig(repo.toString(), false, false, "git status --short", ""))),
                "blackbox-runner");
    }

    private static ProcessRunner.ProcessResult ok(String stdout) {

        return new ProcessRunner.ProcessResult(0, stdout, "", false);
    }

    private static ProcessRunner.ProcessResult failed() {

        return new ProcessRunner.ProcessResult(128, null, "fatal: not a valid ref", false);
    }

    private static TaskSnapshot snapshot(Path repo, String taskId, String lane, TaskStatus status) {
        Instant now = Instant.parse("2026-07-16T12:00:00Z");
        Task task = new Task(
                taskId,
                "spec-1",
                repo.toString(),
                "Story task",
                lane,
                status,
                10,
                "nathan",
                "blackbox-runner",
                null,
                null,
                now,
                now);
        TaskSpec spec = new TaskSpec(
                "spec-1",
                repo.toString(),
                "Story",
                "---\nmode: sdlc\n---\n# Story\n",
                null,
                SpecStatus.ACTIVE,
                "nathan",
                now,
                now);

        return new TaskSnapshot(task, spec);
    }

    private static final class RecordingApiClient extends BlackBoxApiClient {

        private final TaskSnapshot build;
        private final TaskSnapshot review;
        private final List<TaskEvent> buildEvents;

        private RecordingApiClient(TaskSnapshot build, TaskSnapshot review) {
            this(build, review, List.of());
        }

        private RecordingApiClient(TaskSnapshot build, TaskSnapshot review, List<TaskEvent> buildEvents) {
            super(new ObjectMapper());
            this.build = build;
            this.review = review;
            this.buildEvents = buildEvents;
        }

        @Override
        public List<TaskSnapshot> listTasks(String status) {

            return "in_progress".equals(status) && build.task().status() == TaskStatus.IN_PROGRESS
                    ? List.of(build)
                    : List.of();
        }

        @Override
        public List<TaskSnapshot> listTasks(String status, String lane) {

            return switch (lane) {
                case "sdlc:review" -> review == null ? List.of() : List.of(review);
                case "auto" -> List.of(build);
                default -> List.of();
            };
        }

        @Override
        public List<TaskEvent> taskEvents(String taskId) {

            return build.task().id().equals(taskId) ? buildEvents : List.of();
        }

        @Override
        public TaskChange updateTaskStatus(String taskId, String actor, String status, String blockedReason) {

            return null;
        }

        @Override
        public TaskAnnotation annotate(
                String taskId, String actor, String kind, String text, Map<String, Object> dataJson) {

            return null;
        }
    }

    /**
     * Answers git probes by matching a fragment of the joined command line, and records every argv
     * so a test can assert that the destructive commands were never reached. An unscripted command
     * fails loudly rather than returning a default that would let a test pass for the wrong reason.
     */
    private static final class ScriptedProcessRunner implements ProcessRunner {

        private final Map<String, ProcessResult> responses = new LinkedHashMap<>();
        private final List<String> issued = new ArrayList<>();

        private ScriptedProcessRunner on(String commandFragment, ProcessResult result) {
            responses.put(commandFragment, result);

            return this;
        }

        @Override
        public ProcessResult run(List<String> command, File workingDir, Duration timeout) {
            String joined = String.join(" ", command);
            issued.add(joined);

            return responses.entrySet().stream()
                    .filter(entry -> joined.contains(entry.getKey()))
                    .map(Map.Entry::getValue)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Unscripted command: " + joined));
        }

        private boolean issued(String... parts) {
            String fragment = String.join(" ", parts);

            return issued.stream().anyMatch(command -> command.contains(fragment));
        }

        private String commandFor(String fragment) {

            return issued.stream()
                    .filter(command -> command.contains(fragment))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("No command matched: " + fragment));
        }
    }

    private static final class NoOpTmux implements TmuxController {

        @Override
        public boolean hasSession(String sessionName) {

            return false;
        }

        @Override
        public void killSession(String sessionName) {}

        @Override
        public void newSession(String sessionName, File cwd, int width, int height) {}

        @Override
        public void sendKeys(String sessionName, String text) {}

        @Override
        public String capturePane(String sessionName) {

            return "";
        }
    }
}
