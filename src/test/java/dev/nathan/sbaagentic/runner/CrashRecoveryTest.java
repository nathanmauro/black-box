package dev.nathan.sbaagentic.runner;

import dev.nathan.sbaagentic.runner.internal.client.blackbox.BlackBoxApiClient;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nathan.sbaagentic.runner.gate.StoryFrontmatterParser;
import dev.nathan.sbaagentic.runner.process.ProcessRunner;
import dev.nathan.sbaagentic.runner.process.TmuxController;
import dev.nathan.sbaagentic.runner.run.ActiveRunRegistry;
import dev.nathan.sbaagentic.runner.run.CompletionDetector;
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

class CrashRecoveryTest {

    @TempDir
    Path tempDir;

    @Test
    void adoptsAliveSessionAndHonorsCompletionPostedBeforeRestart() throws Exception {
        Path repo = Files.createDirectories(tempDir.resolve("repo"));
        Path worktree = Files.createDirectories(
                repo.resolve(RunnerNaming.worktreeDirName("build-1")));
        TaskSnapshot build = snapshot(repo, "build-1", "auto", TaskStatus.IN_PROGRESS);
        TaskEvent completion = workerDone(
                "build-1", "done", "Verified before the runner restarted.",
                build.task().updatedAt().plusSeconds(1));
        RecordingApiClient apiClient = new RecordingApiClient(
                build, null, List.of(completion));
        AliveThenDeadTmux tmux = new AliveThenDeadTmux();
        ActiveRunRegistry registry = new ActiveRunRegistry();
        List<Runnable> submitted = new ArrayList<>();
        CrashRecovery recovery = recovery(apiClient, tmux, registry);

        recovery.reconcile(config(repo), "blackbox-runner", submitted::add);

        assertThat(submitted).hasSize(1);
        assertThat(registry.tmuxSessionFor("build-1"))
                .contains(RunnerNaming.tmuxSessionName("build-1"));
        assertThat(apiClient.completedTaskIds).isEmpty();

        submitted.getFirst().run();

        assertThat(apiClient.completedTaskIds).containsExactly("build-1");
        assertThat(apiClient.annotations)
                .extracting(RecordedAnnotation::text)
                .anyMatch(text -> text.contains("reported done"));
        assertThat(registry.tmuxSessionFor("build-1")).isEmpty();
        assertThat(worktree).isDirectory();
    }

    @Test
    void resetsAdoptedTaskWhenSessionEndsWithoutCompletionReport() throws Exception {
        Path repo = Files.createDirectories(tempDir.resolve("repo"));
        Files.createDirectories(repo.resolve(RunnerNaming.worktreeDirName("build-1")));
        TaskSnapshot build = snapshot(repo, "build-1", "auto", TaskStatus.IN_PROGRESS);
        RecordingApiClient apiClient = new RecordingApiClient(build, null);
        AliveThenDeadTmux tmux = new AliveThenDeadTmux();
        ActiveRunRegistry registry = new ActiveRunRegistry();
        List<Runnable> submitted = new ArrayList<>();
        CrashRecovery recovery = recovery(apiClient, tmux, registry);

        recovery.reconcile(config(repo), "blackbox-runner", submitted::add);
        submitted.getFirst().run();

        assertThat(apiClient.statusUpdates)
                .containsExactly(new StatusUpdate("build-1", "open", null));
        assertThat(apiClient.annotations)
                .extracting(RecordedAnnotation::text)
                .anyMatch(text -> text.contains("ended without a completion report")
                        && text.contains("reset to open"));
        assertThat(registry.tmuxSessionFor("build-1")).isEmpty();
    }

    @Test
    void resetsDeadSessionToOpenWithoutSubmittingWatcher() {
        Path repo = tempDir.resolve("repo");
        TaskSnapshot build = snapshot(repo, "build-1", "auto", TaskStatus.IN_PROGRESS);
        RecordingApiClient apiClient = new RecordingApiClient(build, null);
        ActiveRunRegistry registry = new ActiveRunRegistry();
        List<Runnable> submitted = new ArrayList<>();
        CrashRecovery recovery = recovery(apiClient, new NoOpTmux(), registry);

        recovery.reconcile(config(), "blackbox-runner", submitted::add);

        assertThat(submitted).isEmpty();
        assertThat(apiClient.statusUpdates)
                .containsExactly(new StatusUpdate("build-1", "open", null));
        assertThat(apiClient.annotations)
                .extracting(RecordedAnnotation::text)
                .anyMatch(text -> text.contains("not found") && text.contains("reset to open"));
        assertThat(registry.tmuxSessionFor("build-1")).isEmpty();
    }

    @Test
    void resetsAliveSessionWhenConfiguredWorktreeIsMissing() throws Exception {
        Path repo = Files.createDirectories(tempDir.resolve("repo"));
        TaskSnapshot build = snapshot(repo, "build-1", "auto", TaskStatus.IN_PROGRESS);
        RecordingApiClient apiClient = new RecordingApiClient(build, null);
        ActiveRunRegistry registry = new ActiveRunRegistry();
        List<Runnable> submitted = new ArrayList<>();
        CrashRecovery recovery = recovery(apiClient, new AlwaysAliveTmux(), registry);

        recovery.reconcile(config(repo), "blackbox-runner", submitted::add);

        assertThat(submitted).isEmpty();
        assertThat(apiClient.statusUpdates)
                .containsExactly(new StatusUpdate("build-1", "open", null));
        assertThat(apiClient.annotations)
                .extracting(RecordedAnnotation::text)
                .anyMatch(text -> text.contains("worktree")
                        && text.contains("not found under any configured repo")
                        && text.contains("reset to open"));
        assertThat(registry.tmuxSessionFor("build-1")).isEmpty();
    }

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
        CrashRecovery recovery = new CrashRecovery(
                apiClient, new NoOpTmux(), processRunner, new StoryFrontmatterParser());

        recovery.reconcile(
                new RunnerConfig(
                        1,
                        List.of(),
                        null,
                        List.of(new RepoConfig(
                                repo.toString(), false, false, "git status --short", ""))),
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
                        "dataJson", Map.of(
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
        RecordingApiClient apiClient = new RecordingApiClient(
                build, null, List.of(workerDone, buildState));
        ProcessRunner processRunner = (command, workingDir, timeout) -> {
            throw new AssertionError("Deferred worktree should not be pruned: " + command);
        };
        CrashRecovery recovery = new CrashRecovery(
                apiClient, new NoOpTmux(), processRunner, new StoryFrontmatterParser());

        recovery.reconcile(
                new RunnerConfig(
                        1,
                        List.of(),
                        null,
                        List.of(new RepoConfig(
                                repo.toString(), false, false, "git status --short", ""))),
                "blackbox-runner");

        assertThat(worktree).isDirectory();
    }

    private static TaskSnapshot snapshot(
            Path repo, String taskId, String lane, TaskStatus status) {
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

    private CrashRecovery recovery(
            RecordingApiClient apiClient,
            TmuxController tmux,
            ActiveRunRegistry registry) {
        ProcessRunner processRunner = (command, workingDir, timeout) -> {
            throw new AssertionError("Unexpected process execution: " + command);
        };
        return new CrashRecovery(
                apiClient,
                tmux,
                processRunner,
                new StoryFrontmatterParser(),
                new CompletionDetector(apiClient, tmux, processRunner),
                registry);
    }

    private static RunnerConfig config(Path... repos) {
        return new RunnerConfig(
                1,
                List.of(),
                null,
                java.util.Arrays.stream(repos)
                        .map(repo -> new RepoConfig(
                                repo.toString(), false, false, "git status --short", ""))
                        .toList());
    }

    private static TaskEvent workerDone(
            String taskId, String outcome, String text, Instant observedAt) {
        return new TaskEvent(
                "worker-done-" + taskId,
                taskId,
                TaskEventType.NOTE,
                "blackbox-runner-worker",
                null,
                null,
                Map.of(
                        "kind", "progress",
                        "text", text,
                        "dataJson", Map.of("event", "worker_done", "outcome", outcome)),
                observedAt);
    }

    private static final class RecordingApiClient extends BlackBoxApiClient {

        private final TaskSnapshot build;
        private final TaskSnapshot review;
        private final List<TaskEvent> buildEvents;
        private final List<StatusUpdate> statusUpdates = new ArrayList<>();
        private final List<RecordedAnnotation> annotations = new ArrayList<>();
        private final List<String> completedTaskIds = new ArrayList<>();

        private RecordingApiClient(TaskSnapshot build, TaskSnapshot review) {
            this(build, review, List.of());
        }

        private RecordingApiClient(
                TaskSnapshot build, TaskSnapshot review, List<TaskEvent> buildEvents) {
            super(new ObjectMapper());
            this.build = build;
            this.review = review;
            this.buildEvents = buildEvents;
        }

        @Override
        public List<TaskSnapshot> listTasks(String status) {
            return "in_progress".equals(status)
                            && build.task().status() == TaskStatus.IN_PROGRESS
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
        public TaskChange updateTaskStatus(
                String taskId, String actor, String status, String blockedReason) {
            statusUpdates.add(new StatusUpdate(taskId, status, blockedReason));
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
            completedTaskIds.add(taskId);
            return null;
        }

        @Override
        public TaskAnnotation annotate(
                String taskId,
                String actor,
                String kind,
                String text,
                Map<String, Object> dataJson) {
            annotations.add(new RecordedAnnotation(taskId, kind, text));
            return null;
        }
    }

    private static class NoOpTmux implements TmuxController {

        @Override
        public boolean hasSession(String sessionName) {
            return false;
        }

        @Override
        public void killSession(String sessionName) {
        }

        @Override
        public void newSession(String sessionName, File cwd, int width, int height) {
        }

        @Override
        public void sendKeys(String sessionName, String text) {
        }

        @Override
        public String capturePane(String sessionName) {
            return "";
        }
    }

    private static final class AlwaysAliveTmux extends NoOpTmux {

        @Override
        public boolean hasSession(String sessionName) {
            return true;
        }
    }

    private static final class AliveThenDeadTmux extends NoOpTmux {

        private int checks;

        @Override
        public boolean hasSession(String sessionName) {
            return checks++ == 0;
        }
    }

    private record StatusUpdate(String taskId, String status, String blockedReason) {
    }

    private record RecordedAnnotation(String taskId, String kind, String text) {
    }
}
