package dev.nathan.sbaagentic.runner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import dev.nathan.sbaagentic.runner.process.ProcessRunner;
import dev.nathan.sbaagentic.runner.process.TmuxController;
import dev.nathan.sbaagentic.task.TaskSnapshot;

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

    public CrashRecovery(BlackBoxApiClient apiClient, TmuxController tmux, ProcessRunner processRunner) {
        this.apiClient = apiClient;
        this.tmux = tmux;
        this.processRunner = processRunner;
    }

    public void reconcile(RunnerConfig config, String actorId) {
        List<TaskSnapshot> inProgress = apiClient.listTasks("in_progress");
        Set<String> activeWorktreeNames = new HashSet<>();
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

        for (RepoConfig repo : config.repos()) {
            pruneOrphanedWorktrees(repo, activeWorktreeNames);
        }
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
}
