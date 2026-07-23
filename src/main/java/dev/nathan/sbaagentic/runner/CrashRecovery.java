package dev.nathan.sbaagentic.runner;

import dev.nathan.sbaagentic.runner.internal.client.blackbox.BlackBoxApiClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.stream.Stream;

import dev.nathan.sbaagentic.runner.gate.StoryFrontmatterParser;
import dev.nathan.sbaagentic.runner.process.ProcessRunner;
import dev.nathan.sbaagentic.runner.process.TmuxController;
import dev.nathan.sbaagentic.runner.run.ActiveRunRegistry;
import dev.nathan.sbaagentic.runner.run.CompletionDetector;
import dev.nathan.sbaagentic.runner.run.CompletionDetector.CompletionResult;
import dev.nathan.sbaagentic.runner.run.WorkerRunExecutor;
import dev.nathan.sbaagentic.runner.run.WorkerRunExecutor.WorkerOutcome;
import dev.nathan.sbaagentic.runner.run.WorkerRunExecutor.WorkerRunResult;
import dev.nathan.sbaagentic.runner.internal.client.blackbox.Task;
import dev.nathan.sbaagentic.runner.internal.client.blackbox.TaskEvent;
import dev.nathan.sbaagentic.runner.internal.client.blackbox.TaskEventType;
import dev.nathan.sbaagentic.runner.internal.client.blackbox.TaskSnapshot;
import dev.nathan.sbaagentic.runner.internal.client.blackbox.TaskStatus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class CrashRecovery {

    private static final Logger log = LoggerFactory.getLogger(CrashRecovery.class);
    private static final Duration GIT_TIMEOUT = Duration.ofSeconds(30);

    private final BlackBoxApiClient apiClient;
    private final TmuxController tmux;
    private final ProcessRunner processRunner;
    private final StoryFrontmatterParser frontmatterParser;
    private final CompletionDetector completionDetector;
    private final ActiveRunRegistry activeRunRegistry;

    @Autowired
    public CrashRecovery(
            BlackBoxApiClient apiClient,
            TmuxController tmux,
            ProcessRunner processRunner,
            StoryFrontmatterParser frontmatterParser,
            CompletionDetector completionDetector,
            ActiveRunRegistry activeRunRegistry) {
        this.apiClient = apiClient;
        this.tmux = tmux;
        this.processRunner = processRunner;
        this.frontmatterParser = frontmatterParser;
        this.completionDetector = completionDetector;
        this.activeRunRegistry = activeRunRegistry;
    }

    CrashRecovery(
            BlackBoxApiClient apiClient,
            TmuxController tmux,
            ProcessRunner processRunner,
            StoryFrontmatterParser frontmatterParser) {
        this(
                apiClient,
                tmux,
                processRunner,
                frontmatterParser,
                new CompletionDetector(apiClient, tmux, processRunner),
                new ActiveRunRegistry());
    }

    public void reconcile(RunnerConfig config, String actorId) {
        reconcile(config, actorId, Runnable::run);
    }

    public void reconcile(
            RunnerConfig config,
            String actorId,
            Executor adoptedWatcherExecutor) {
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
                Optional<Path> worktree = configuredWorktree(config, taskId);
                if (worktree.isEmpty()) {
                    resetToOpen(
                            taskId,
                            actorId,
                            "Crash recovery: tmux session " + sessionName
                                    + " is alive, but worktree " + RunnerNaming.worktreeDirName(taskId)
                                    + " was not found under any configured repo; task reset to open.");
                    continue;
                }
                activeWorktreeNames.add(worktreeName);
                adopt(
                        snapshot.task(),
                        worktree.orElseThrow(),
                        sessionName,
                        actorId,
                        adoptedWatcherExecutor);
                continue;
            }

            resetToOpen(
                    taskId,
                    actorId,
                    "Crash recovery: tmux session " + sessionName
                            + " not found; task reset to open.");
        }

        if (sdlcStateKnown) {
            for (RepoConfig repo : safeList(config.repos())) {
                pruneOrphanedWorktrees(repo, activeWorktreeNames);
            }
        }
        else {
            log.warn("Skipping orphaned worktree pruning because SDLC preservation state is unavailable");
        }
    }

    private Optional<Path> configuredWorktree(RunnerConfig config, String taskId) {
        for (RepoConfig repo : safeList(config.repos())) {
            if (repo == null || repo.path() == null || repo.path().isBlank()) {
                continue;
            }
            Path repoPath = Path.of(repo.path()).toAbsolutePath().normalize();
            Path worktreesRoot = repoPath.resolve(".worktrees").normalize();
            Path candidate = repoPath.resolve(RunnerNaming.worktreeDirName(taskId)).normalize();
            if (candidate.startsWith(worktreesRoot) && Files.isDirectory(candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private void adopt(
            Task task,
            Path worktree,
            String sessionName,
            String actorId,
            Executor adoptedWatcherExecutor) {
        Instant since = task.updatedAt() == null ? Instant.EPOCH : task.updatedAt();
        activeRunRegistry.register(task.id(), sessionName);
        annotateBestEffort(
                task.id(),
                actorId,
                "Crash recovery: adopted live tmux session " + sessionName
                        + " and resumed completion watching.");
        try {
            adoptedWatcherExecutor.execute(
                    () -> watchAdopted(task, worktree, sessionName, actorId, since));
        }
        catch (RuntimeException ex) {
            activeRunRegistry.deregister(task.id(), sessionName);
            log.error("Unable to submit adopted watcher for task {}; resetting it to open", task.id(), ex);
            resetToOpenBestEffort(
                    task.id(),
                    actorId,
                    "Crash recovery: unable to start a watcher for live tmux session "
                            + sessionName + "; task reset to open.");
        }
    }

    private void watchAdopted(
            Task task,
            Path worktree,
            String sessionName,
            String actorId,
            Instant since) {
        try {
            CompletionResult completion = completionDetector.awaitCompletion(
                    task.id(),
                    sessionName,
                    worktree.toFile(),
                    WorkerRunExecutor.runTimeout(),
                    WorkerRunExecutor.completionPollInterval(),
                    since);
            WorkerRunResult result = WorkerRunExecutor.finalizeCompletion(
                    completion, sessionName, since);
            applyAdoptedOutcome(task, sessionName, actorId, completion, result);
        }
        catch (RuntimeException ex) {
            log.error("Adopted watcher failed for task {}; resetting it to open", task.id(), ex);
            resetToOpenBestEffort(
                    task.id(),
                    actorId,
                    "Crash recovery: adopted watcher failed: " + message(ex)
                            + "; task reset to open.");
        }
        finally {
            activeRunRegistry.deregister(task.id(), sessionName);
        }
    }

    private void applyAdoptedOutcome(
            Task task,
            String sessionName,
            String actorId,
            CompletionResult completion,
            WorkerRunResult result) {
        if (result.outcome() == WorkerOutcome.DONE) {
            apiClient.completeTask(
                    task.id(),
                    actorId,
                    "cli",
                    "blackbox-runner-recovery-" + task.id(),
                    isBlank(result.detail())
                            ? "Adopted worker reported done after runner restart."
                            : result.detail(),
                    List.of(),
                    "Review the recovered worker result and continue any stage-specific follow-up.");
            annotateBestEffort(
                    task.id(),
                    actorId,
                    "Crash recovery: adopted run reported done; task finalized from its completion report.");
            killSessionBestEffort(sessionName);
            return;
        }

        if (result.outcome() == WorkerOutcome.BLOCKED && completion.reported()) {
            apiClient.updateTaskStatus(task.id(), actorId, "blocked", result.detail());
            annotateBestEffort(
                    task.id(),
                    actorId,
                    "Crash recovery: adopted run reported blocked; task marked blocked.");
            killSessionBestEffort(sessionName);
            return;
        }

        if (result.outcome() == WorkerOutcome.BLOCKED) {
            if (!tmux.hasSession(sessionName)) {
                resetToOpen(
                        task.id(),
                        actorId,
                        "Crash recovery: adopted tmux session " + sessionName
                                + " ended without a completion report; task reset to open.");
            }
            else {
                log.info(
                        "Adopted watcher for task {} was interrupted while tmux session {} remains alive; "
                                + "leaving the task in progress for the next recovery pass",
                        task.id(),
                        sessionName);
            }
            return;
        }

        if (result.outcome() == WorkerOutcome.TIMED_OUT) {
            String reason = "Run timed out after 45m. " + result.detail();
            apiClient.updateTaskStatus(task.id(), actorId, "blocked", reason);
            annotateBestEffort(
                    task.id(),
                    actorId,
                    "Crash recovery: adopted run timed out and was marked blocked.");
            killSessionBestEffort(sessionName);
            return;
        }

        throw new IllegalStateException(
                "Unexpected adopted worker outcome " + result.outcome());
    }

    private void resetToOpen(String taskId, String actorId, String annotation) {
        apiClient.updateTaskStatus(taskId, actorId, "open", null);
        apiClient.annotate(taskId, actorId, "progress", annotation, null);
    }

    private void resetToOpenBestEffort(String taskId, String actorId, String annotation) {
        try {
            resetToOpen(taskId, actorId, annotation);
        }
        catch (RuntimeException ex) {
            log.error("Unable to reset task {} to open during crash recovery", taskId, ex);
        }
    }

    private void annotateBestEffort(String taskId, String actorId, String annotation) {
        try {
            apiClient.annotate(taskId, actorId, "progress", annotation, null);
        }
        catch (RuntimeException ex) {
            log.warn("Unable to annotate crash recovery state for task {}", taskId, ex);
        }
    }

    private void killSessionBestEffort(String sessionName) {
        try {
            if (tmux.hasSession(sessionName)) {
                tmux.killSession(sessionName);
            }
        }
        catch (RuntimeException ex) {
            log.warn("Unable to kill adopted tmux session {}", sessionName, ex);
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

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String message(Throwable error) {
        return error.getMessage() == null || error.getMessage().isBlank()
                ? error.getClass().getSimpleName()
                : error.getMessage();
    }
}
