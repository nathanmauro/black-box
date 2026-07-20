package dev.nathan.sbaagentic.runner.internal.application;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import dev.nathan.sbaagentic.runner.RepoConfig;
import dev.nathan.sbaagentic.runner.RunnerConfig;
import dev.nathan.sbaagentic.runner.RunnerNaming;
import dev.nathan.sbaagentic.runner.internal.application.ApprovalInterpreter.ShipMarker;
import dev.nathan.sbaagentic.runner.internal.client.blackbox.BlackBoxApiClient;
import dev.nathan.sbaagentic.runner.internal.client.blackbox.Task;
import dev.nathan.sbaagentic.runner.internal.client.blackbox.TaskEvent;
import dev.nathan.sbaagentic.runner.internal.client.blackbox.TaskEventType;
import dev.nathan.sbaagentic.runner.internal.client.blackbox.TaskSnapshot;
import dev.nathan.sbaagentic.runner.internal.client.blackbox.TaskStatus;
import dev.nathan.sbaagentic.runner.process.ProcessRunner;
import dev.nathan.sbaagentic.runner.process.ProcessRunner.ProcessResult;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SdlcReconciliationState {

    private static final Logger log = LoggerFactory.getLogger(SdlcReconciliationState.class);
    private static final Duration GIT_TIMEOUT = Duration.ofSeconds(30);
    private static final String BUILD_LANE = "auto";

    private final BlackBoxApiClient apiClient;
    private final ProcessRunner processRunner;

    public SdlcReconciliationState(BlackBoxApiClient apiClient, ProcessRunner processRunner) {
        this.apiClient = apiClient;
        this.processRunner = processRunner;
    }

    public Optional<Task> existingBuildTask(String specId) {
        return safeList(apiClient.listTasks(null, BUILD_LANE)).stream()
                .filter(Objects::nonNull)
                .map(TaskSnapshot::task)
                .filter(Objects::nonNull)
                .filter(task -> specId.equals(task.specId()))
                .filter(task -> task.status() == TaskStatus.OPEN
                        || task.status() == TaskStatus.IN_PROGRESS
                        || task.status() == TaskStatus.DONE
                        || task.status() == TaskStatus.BLOCKED)
                .findFirst();
    }

    public Optional<BuildState> findBuildState(String specId, String actorId) {
        List<Task> builds = safeList(apiClient.listTasks(null, BUILD_LANE)).stream()
                .filter(Objects::nonNull)
                .map(TaskSnapshot::task)
                .filter(Objects::nonNull)
                .filter(task -> specId.equals(task.specId()))
                .filter(task -> task.status() == TaskStatus.DONE)
                .sorted(Comparator.comparing(
                                Task::createdAt,
                                Comparator.nullsFirst(Comparator.naturalOrder()))
                        .reversed())
                .toList();
        for (Task build : builds) {
            Optional<BuildState> state = latestBuildState(
                    safeList(apiClient.taskEvents(build.id())), actorId, build);
            if (state.isPresent()) {
                return state;
            }
        }
        return Optional.empty();
    }

    public Optional<BuildState> latestBuildState(List<TaskEvent> events, String actorId, Task build) {
        for (int index = events.size() - 1; index >= 0; index--) {
            TaskEvent event = events.get(index);
            if (!isRunnerProgress(event, actorId)) {
                continue;
            }
            Map<?, ?> data = dataJson(event);
            String branch = stringValue(data.get("branch"));
            String worktree = stringValue(data.get("worktree"));
            if (!isBlank(branch) && !isBlank(worktree)) {
                return Optional.of(new BuildState(branch, worktree, build.id(), build.title()));
            }
        }
        return Optional.empty();
    }

    public Optional<RepoConfig> matchingRepo(RunnerConfig config, String repoPath) {
        if (config == null || isBlank(repoPath)) {
            return Optional.empty();
        }
        return safeList(config.repos()).stream()
                .filter(Objects::nonNull)
                .filter(repo -> Objects.equals(repo.path(), repoPath))
                .findFirst();
    }

    public Optional<Path> validatedWorktree(RepoConfig repoConfig, BuildState state, boolean requireClean) {
        Optional<Path> normalized = normalizedWorktree(repoConfig, state.worktree());
        if (normalized.isEmpty() || !Files.isDirectory(normalized.orElseThrow())) {
            return Optional.empty();
        }
        Path worktree = normalized.orElseThrow();
        Path expected = Path.of(repoConfig.path())
                .toAbsolutePath()
                .normalize()
                .resolve(RunnerNaming.worktreeDirName(state.buildTaskId()))
                .normalize();
        if (!expected.equals(worktree)
                || !WorktreeManager.branchName(state.buildTitle(), state.buildTaskId()).equals(state.branch())) {
            return Optional.empty();
        }
        try {
            Path realRoot = Path.of(repoConfig.path())
                    .toAbsolutePath()
                    .normalize()
                    .resolve(".worktrees")
                    .toRealPath();
            if (!worktree.toRealPath().startsWith(realRoot)) {
                return Optional.empty();
            }
        }
        catch (IOException ex) {
            return Optional.empty();
        }
        ProcessResult branch = processRunner.run(
                List.of("git", "-C", worktree.toString(), "rev-parse", "--abbrev-ref", "HEAD"),
                worktree.toFile(),
                GIT_TIMEOUT);
        if (branch.timedOut()
                || branch.exitCode() != 0
                || !state.branch().equals(safeStrip(branch.stdout()))) {
            return Optional.empty();
        }
        if (requireClean && !isClean(worktree)) {
            return Optional.empty();
        }
        return Optional.of(worktree);
    }

    public void pruneMergedWorktreeIfNeeded(
            String taskId,
            String actorId,
            String repoPath,
            RunnerConfig config,
            ShipMarker marker) {
        if (!"merged".equals(marker.status()) || isBlank(marker.worktree())) {
            return;
        }
        Optional<RepoConfig> repoConfig = matchingRepo(config, repoPath);
        if (repoConfig.isEmpty()) {
            return;
        }
        Optional<Path> worktree = normalizedWorktree(repoConfig.orElseThrow(), marker.worktree());
        worktree.filter(Files::isDirectory)
                .ifPresent(path -> pruneMergedWorktree(taskId, actorId, repoConfig.orElseThrow(), path));
    }

    public void pruneMergedWorktree(
            String taskId, String actorId, RepoConfig repoConfig, Path worktree) {
        if (!Files.isDirectory(worktree) || !isClean(worktree)) {
            return;
        }
        File repoDir = new File(repoConfig.path());
        ProcessResult remove = processRunner.run(
                List.of("git", "-C", repoDir.getAbsolutePath(), "worktree", "remove",
                        worktree.toString(), "--force"),
                repoDir,
                GIT_TIMEOUT);
        if (remove.timedOut() || remove.exitCode() != 0) {
            log.warn("Unable to remove merged SDLC worktree {}: {}", worktree, processDetail(remove));
            return;
        }
        ProcessResult prune = processRunner.run(
                List.of("git", "-C", repoDir.getAbsolutePath(), "worktree", "prune"),
                repoDir,
                GIT_TIMEOUT);
        if (prune.timedOut() || prune.exitCode() != 0) {
            log.warn("Unable to prune merged SDLC worktree metadata in {}: {}", repoDir, processDetail(prune));
            return;
        }
        try {
            apiClient.annotate(
                    taskId,
                    actorId,
                    "progress",
                    "Merged SDLC worktree removed and pruned: " + worktree,
                    Map.of("sdlc", "worktree_pruned", "worktree", worktree.toString()));
        }
        catch (RuntimeException ex) {
            log.warn("Unable to annotate merged SDLC worktree cleanup for task {}", taskId, ex);
        }
    }

    private Optional<Path> normalizedWorktree(RepoConfig repoConfig, String worktreeValue) {
        if (repoConfig == null || isBlank(repoConfig.path()) || isBlank(worktreeValue)) {
            return Optional.empty();
        }
        try {
            Path worktree = Path.of(worktreeValue);
            if (!worktree.isAbsolute()) {
                return Optional.empty();
            }
            Path repoRoot = Path.of(repoConfig.path()).toAbsolutePath().normalize();
            Path normalized = worktree.normalize();
            if (!normalized.startsWith(repoRoot.resolve(".worktrees").normalize())) {
                return Optional.empty();
            }
            return Optional.of(normalized);
        }
        catch (RuntimeException ex) {
            return Optional.empty();
        }
    }

    private boolean isClean(Path worktree) {
        try {
            ProcessResult status = processRunner.run(
                    List.of("git", "-C", worktree.toString(), "status", "--porcelain"),
                    worktree.toFile(),
                    GIT_TIMEOUT);
            return !status.timedOut() && status.exitCode() == 0 && safeStrip(status.stdout()).isBlank();
        }
        catch (RuntimeException ex) {
            log.warn("Unable to verify SDLC worktree cleanliness at {}", worktree, ex);
            return false;
        }
    }

    private static boolean isRunnerProgress(TaskEvent event, String actorId) {
        return event != null
                && Objects.equals(actorId, event.actor())
                && event.type() == TaskEventType.NOTE
                && event.detail() != null
                && "progress".equals(stringValue(event.detail().get("kind")));
    }

    private static Map<?, ?> dataJson(TaskEvent event) {
        Object data = event == null || event.detail() == null ? null : event.detail().get("dataJson");
        return data instanceof Map<?, ?> map ? map : Map.of();
    }

    private static String processDetail(ProcessResult result) {
        String output = result.stderr() != null && !result.stderr().isBlank()
                ? result.stderr().strip()
                : safeStrip(result.stdout());
        return "exit " + result.exitCode()
                + (result.timedOut() ? ", timed out" : "")
                + (output.isBlank() ? "" : ": " + output);
    }

    private static String stringValue(Object value) {
        return value instanceof String string ? string : null;
    }

    private static String safeStrip(String value) {
        return value == null ? "" : value.strip();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    public record BuildState(String branch, String worktree, String buildTaskId, String buildTitle) {
    }
}
