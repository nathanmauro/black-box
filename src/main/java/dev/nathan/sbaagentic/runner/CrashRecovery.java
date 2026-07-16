package dev.nathan.sbaagentic.runner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

import dev.nathan.sbaagentic.runner.gate.StoryFrontmatterParser;
import dev.nathan.sbaagentic.runner.process.ProcessRunner;
import dev.nathan.sbaagentic.runner.process.TmuxController;
import dev.nathan.sbaagentic.task.Task;
import dev.nathan.sbaagentic.task.TaskEvent;
import dev.nathan.sbaagentic.task.TaskEventType;
import dev.nathan.sbaagentic.task.TaskSnapshot;
import dev.nathan.sbaagentic.task.TaskStatus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Component;

@Component
public class CrashRecovery {

    private static final Logger log = LoggerFactory.getLogger(CrashRecovery.class);
    private static final Duration GIT_TIMEOUT = Duration.ofSeconds(30);

    private final BlackBoxApiClient apiClient;
    private final TmuxController tmux;
    private final ProcessRunner processRunner;
    private final StoryFrontmatterParser frontmatterParser;

    public CrashRecovery(
            BlackBoxApiClient apiClient,
            TmuxController tmux,
            ProcessRunner processRunner,
            StoryFrontmatterParser frontmatterParser) {
        this.apiClient = apiClient;
        this.tmux = tmux;
        this.processRunner = processRunner;
        this.frontmatterParser = frontmatterParser;
    }

    public void reconcile(RunnerConfig config, String actorId) {
        List<TaskSnapshot> inProgress = apiClient.listTasks("in_progress");
        Set<String> activeWorktreeNames = new HashSet<>();
        boolean sdlcStateKnown = protectSdlcReviewWorktrees(activeWorktreeNames, actorId);
        for (TaskSnapshot snapshot : inProgress) {
            String taskId = snapshot.task().id();
            String worktreeName = Path.of(RunnerNaming.worktreeDirName(taskId)).getFileName().toString();
            if (!actorId.equals(snapshot.task().claimedBy())) {
                activeWorktreeNames.add(worktreeName);
                continue;
            }

            String sessionName = RunnerNaming.tmuxSessionName(taskId);
            if (tmux.hasSession(sessionName)) {
                activeWorktreeNames.add(worktreeName);
                continue;
            }

            apiClient.updateTaskStatus(taskId, actorId, "open", null);
            apiClient.annotate(
                    taskId,
                    actorId,
                    "progress",
                    "Crash recovery: tmux session " + sessionName + " not found; task reset to open.",
                    null);
        }

        if (sdlcStateKnown) {
            for (RepoConfig repo : config.repos()) {
                pruneOrphanedWorktrees(repo, activeWorktreeNames);
            }
        }
        else {
            log.warn("Skipping orphaned worktree pruning because SDLC preservation state is unavailable");
        }
    }

    private boolean protectSdlcReviewWorktrees(
            Set<String> activeWorktreeNames, String actorId) {
        List<TaskSnapshot> reviews;
        List<TaskSnapshot> builds;
        try {
            reviews = safeList(apiClient.listTasks(null, "sdlc:review"));
            builds = safeList(apiClient.listTasks(null, "auto"));
        }
        catch (RuntimeException ex) {
            log.warn("Unable to read SDLC tasks during crash recovery", ex);
            return false;
        }

        Set<String> preservedSpecIds = reviews.stream()
                .filter(snapshot -> snapshot != null && snapshot.task() != null)
                .filter(snapshot -> snapshot.task().status() != TaskStatus.CANCELLED)
                .map(snapshot -> snapshot.task().specId())
                .collect(java.util.stream.Collectors.toSet());

        for (TaskSnapshot snapshot : builds) {
            if (snapshot == null || snapshot.task() == null) {
                continue;
            }
            Task task = snapshot.task();
            if (preservedSpecIds.contains(task.specId())
                    || hasDeferredBuildMarker(snapshot, actorId)) {
                activeWorktreeNames.add(Path.of(RunnerNaming.worktreeDirName(task.id()))
                        .getFileName()
                        .toString());
            }
        }
        return true;
    }

    private boolean hasDeferredBuildMarker(TaskSnapshot snapshot, String actorId) {
        Task task = snapshot.task();
        if (task.status() == TaskStatus.CANCELLED || !isSdlc(snapshot)) {
            return false;
        }
        String expectedName = Path.of(RunnerNaming.worktreeDirName(task.id()))
                .getFileName()
                .toString();
        List<TaskEvent> events;
        try {
            events = safeList(apiClient.taskEvents(task.id()));
        }
        catch (RuntimeException ex) {
            log.warn(
                    "Unable to verify deferred SDLC build state for task {}; preserving its worktree",
                    task.id(),
                    ex);
            return true;
        }
        boolean workerDone = events.stream().anyMatch(event -> {
            if (event == null
                    || event.type() != TaskEventType.NOTE
                    || !"blackbox-runner-worker".equals(event.actor())
                    || event.detail() == null
                    || !"progress".equals(event.detail().get("kind"))) {
                return false;
            }
            Object rawData = event.detail().get("dataJson");
            if (!(rawData instanceof Map<?, ?> data)) {
                return false;
            }
            return "worker_done".equals(data.get("event"))
                    && "done".equals(data.get("outcome"));
        });
        if (!workerDone) {
            return false;
        }
        return events.stream().anyMatch(event -> {
            if (event == null
                    || event.type() != TaskEventType.NOTE
                    || !Objects.equals(actorId, event.actor())
                    || event.detail() == null
                    || !"progress".equals(event.detail().get("kind"))) {
                return false;
            }
            Object rawData = event.detail().get("dataJson");
            if (!(rawData instanceof Map<?, ?> data)) {
                return false;
            }
            Object branch = data.get("branch");
            Object worktree = data.get("worktree");
            if (!(branch instanceof String branchValue)
                    || branchValue.isBlank()
                    || !(worktree instanceof String worktreeValue)
                    || worktreeValue.isBlank()) {
                return false;
            }
            try {
                return RunExecutor.branchName(task.title(), task.id()).equals(branchValue)
                        && expectedName.equals(Path.of(worktreeValue).getFileName().toString());
            }
            catch (RuntimeException ex) {
                return false;
            }
        });
    }

    private boolean isSdlc(TaskSnapshot snapshot) {
        if (snapshot.spec() == null) {
            log.warn(
                    "Auto task {} has no attached spec during crash recovery; preserving conservatively",
                    snapshot.task().id());
            return true;
        }
        return frontmatterParser.parse(snapshot.spec().body())
                .map(parsed -> "sdlc".equals(parsed.frontmatter().mode()))
                .orElse(false);
    }

    private void pruneOrphanedWorktrees(RepoConfig repo, Set<String> activeWorktreeNames) {
        if (repo == null || repo.path() == null || repo.path().isBlank()) {
            return;
        }
        Path repoPath = Path.of(repo.path()).toAbsolutePath().normalize();
        if (!Files.isDirectory(repoPath)) {
            return;
        }
        Path worktreesPath = repoPath.resolve(".worktrees");
        if (!Files.isDirectory(worktreesPath)) {
            return;
        }

        try (Stream<Path> children = Files.list(worktreesPath)) {
            children.filter(Files::isDirectory)
                    .filter(path -> path.getFileName().toString().startsWith("bb-"))
                    .filter(path -> !activeWorktreeNames.contains(path.getFileName().toString()))
                    .forEach(path -> pruneIfClean(repoPath, path));
        }
        catch (IOException ex) {
            log.warn("Unable to inspect runner worktrees under {}: {}", worktreesPath, ex.getMessage());
        }
    }

    private void pruneIfClean(Path repoPath, Path worktreePath) {
        ProcessRunner.ProcessResult status = processRunner.run(
                List.of("git", "-C", worktreePath.toString(), "status", "--porcelain"),
                worktreePath.toFile(),
                GIT_TIMEOUT);
        if (status.exitCode() != 0
                || status.timedOut()
                || status.stdout() == null
                || !status.stdout().isBlank()) {
            log.warn("orphaned dirty worktree left for inspection: {}", worktreePath);
            return;
        }

        String branchName = currentBranch(worktreePath);
        ProcessRunner.ProcessResult remove = processRunner.run(
                List.of(
                        "git",
                        "-C",
                        repoPath.toString(),
                        "worktree",
                        "remove",
                        worktreePath.toString(),
                        "--force"),
                repoPath.toFile(),
                GIT_TIMEOUT);
        if (remove.exitCode() != 0 || remove.timedOut()) {
            log.warn("Unable to remove clean orphaned worktree {}: {}", worktreePath, remove.stderr());
            return;
        }
        ProcessRunner.ProcessResult prune = processRunner.run(
                List.of("git", "-C", repoPath.toString(), "worktree", "prune"),
                repoPath.toFile(),
                GIT_TIMEOUT);
        if (prune.exitCode() != 0 || prune.timedOut()) {
            log.warn("Unable to prune worktree metadata for {}: {}", repoPath, prune.stderr());
            return;
        }
        if (branchName != null && !branchName.isBlank() && !"HEAD".equals(branchName)) {
            ProcessRunner.ProcessResult deleteBranch = processRunner.run(
                    List.of("git", "-C", repoPath.toString(), "branch", "-D", branchName),
                    repoPath.toFile(),
                    GIT_TIMEOUT);
            if (deleteBranch.exitCode() != 0 || deleteBranch.timedOut()) {
                log.warn("Unable to delete orphaned branch {}: {}", branchName, deleteBranch.stderr());
            }
        }
    }

    private String currentBranch(Path worktreePath) {
        ProcessRunner.ProcessResult branch = processRunner.run(
                List.of("git", "-C", worktreePath.toString(), "rev-parse", "--abbrev-ref", "HEAD"),
                worktreePath.toFile(),
                GIT_TIMEOUT);
        if (branch.exitCode() != 0 || branch.timedOut() || branch.stdout() == null) {
            return null;
        }
        return branch.stdout().strip();
    }

    private static <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }
}
