package dev.nathan.sbaagentic.runner.internal.application;

import java.io.File;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

import dev.nathan.sbaagentic.runner.RunnerNaming;
import dev.nathan.sbaagentic.runner.internal.client.blackbox.BlackBoxApiClient;
import dev.nathan.sbaagentic.runner.internal.client.blackbox.Task;
import dev.nathan.sbaagentic.runner.process.ProcessRunner;
import dev.nathan.sbaagentic.runner.process.ProcessRunner.ProcessResult;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WorktreeManager {

    private static final Logger log = LoggerFactory.getLogger(WorktreeManager.class);
    private static final Duration GIT_TIMEOUT = Duration.ofSeconds(30);

    private final BlackBoxApiClient apiClient;
    private final ProcessRunner processRunner;

    public WorktreeManager(BlackBoxApiClient apiClient, ProcessRunner processRunner) {
        this.apiClient = apiClient;
        this.processRunner = processRunner;
    }

    public boolean prepareWorktree(
            Task task,
            String actorId,
            File repoDir,
            File worktreeDir,
            String branchName) {
        File worktreeParent = worktreeDir.getParentFile();
        if (worktreeParent != null
                && !worktreeParent.isDirectory()
                && !worktreeParent.mkdirs()) {
            block(task.id(), actorId, "Unable to create worktree parent directory: " + worktreeParent);
            return false;
        }
        ProcessResult worktreeResult = createWorktree(repoDir, worktreeDir, branchName);
        if (worktreeResult.exitCode() != 0 || worktreeResult.timedOut()) {
            block(task.id(), actorId, "Unable to create git worktree: " + processDetail(worktreeResult));
            return false;
        }
        apiClient.annotate(
                task.id(),
                actorId,
                "progress",
                "Worktree created at " + worktreeDir.getAbsolutePath() + " on branch " + branchName + ".",
                null);
        return true;
    }

    public ProcessResult createWorktree(File repoDir, File worktreeDir, String branchName) {
        ProcessResult defaultBranchResult = processRunner.run(
                List.of(
                        "git", "-C", repoDir.getAbsolutePath(), "rev-parse", "--abbrev-ref", "origin/HEAD"),
                repoDir,
                GIT_TIMEOUT);
        String defaultBranch;
        if (!defaultBranchResult.timedOut()
                && defaultBranchResult.exitCode() == 0
                && !defaultBranchResult.stdout().isBlank()) {
            defaultBranch = defaultBranchResult.stdout().strip();
            if (defaultBranch.startsWith("origin/")) {
                defaultBranch = defaultBranch.substring("origin/".length());
            }
        }
        else {
            ProcessResult localBranchResult = processRunner.run(
                    List.of("git", "-C", repoDir.getAbsolutePath(), "symbolic-ref", "--short", "HEAD"),
                    repoDir,
                    GIT_TIMEOUT);
            if (localBranchResult.timedOut()
                    || localBranchResult.exitCode() != 0
                    || localBranchResult.stdout().isBlank()) {
                return localBranchResult;
            }
            defaultBranch = localBranchResult.stdout().strip();
        }
        return processRunner.run(
                List.of(
                        "git", "-C", repoDir.getAbsolutePath(), "worktree", "add",
                        worktreeDir.getAbsolutePath(), "-b", branchName, defaultBranch),
                repoDir,
                GIT_TIMEOUT);
    }

    public static String branchName(String title, String taskId) {
        String slug = title == null
                ? ""
                : title.toLowerCase(Locale.ROOT)
                        .replaceAll("[^a-z0-9]+", "-")
                        .replaceAll("^-+|-+$", "");
        if (slug.length() > 40) {
            slug = slug.substring(0, 40).replaceAll("-+$", "");
        }
        if (slug.isBlank()) {
            slug = "task";
        }
        return "auto/" + slug + "-" + RunnerNaming.taskShort(taskId);
    }

    public boolean validPreservedWorktree(
            File repoDir,
            Task buildTask,
            String artifactBranch,
            File artifactWorktree) {
        if (artifactWorktree == null
                || !artifactWorktree.isDirectory()
                || !branchName(buildTask.title(), buildTask.id()).equals(artifactBranch)) {
            return false;
        }
        Path expected = new File(repoDir, RunnerNaming.worktreeDirName(buildTask.id()))
                .toPath()
                .toAbsolutePath()
                .normalize();
        Path actual = artifactWorktree.toPath().toAbsolutePath().normalize();
        if (!expected.equals(actual)) {
            return false;
        }
        ProcessResult branch = processRunner.run(
                List.of("git", "-C", artifactWorktree.getAbsolutePath(), "branch", "--show-current"),
                artifactWorktree,
                GIT_TIMEOUT);
        if (branch.timedOut()
                || branch.exitCode() != 0
                || !artifactBranch.equals(safeStrip(branch.stdout()))) {
            return false;
        }
        ProcessResult status = processRunner.run(
                List.of("git", "-C", artifactWorktree.getAbsolutePath(), "status", "--porcelain"),
                artifactWorktree,
                GIT_TIMEOUT);
        return !status.timedOut() && status.exitCode() == 0 && safeStrip(status.stdout()).isBlank();
    }

    public GitState gitState(File worktreeDir) {
        ProcessResult head = processRunner.run(
                List.of("git", "-C", worktreeDir.getAbsolutePath(), "rev-parse", "HEAD"),
                worktreeDir,
                GIT_TIMEOUT);
        ProcessResult status = processRunner.run(
                List.of("git", "-C", worktreeDir.getAbsolutePath(), "status", "--porcelain"),
                worktreeDir,
                GIT_TIMEOUT);
        if (head.timedOut() || head.exitCode() != 0 || status.timedOut() || status.exitCode() != 0) {
            throw new IllegalStateException(
                    "Unable to verify stage worktree state: head=" + processDetail(head)
                            + ", status=" + processDetail(status));
        }
        return new GitState(safeStrip(head.stdout()), status.stdout() == null ? "" : status.stdout());
    }

    public void pruneMergedWorktree(String taskId, String actorId, File repoDir, File worktreeDir) {
        ProcessResult status = processRunner.run(
                List.of("git", "-C", worktreeDir.getAbsolutePath(), "status", "--porcelain"),
                worktreeDir,
                GIT_TIMEOUT);
        if (status.timedOut() || status.exitCode() != 0) {
            annotateBestEffort(taskId, actorId,
                    "Merged PR, but left the worktree in place because cleanliness could not be verified: "
                            + processDetail(status));
            return;
        }
        if (!status.stdout().isBlank()) {
            annotateBestEffort(taskId, actorId,
                    "Merged PR, but left the dirty worktree in place: " + worktreeDir.getAbsolutePath());
            return;
        }
        ProcessResult remove = processRunner.run(
                List.of("git", "-C", repoDir.getAbsolutePath(), "worktree", "remove",
                        worktreeDir.getAbsolutePath(), "--force"),
                repoDir,
                GIT_TIMEOUT);
        if (remove.timedOut() || remove.exitCode() != 0) {
            annotateBestEffort(taskId, actorId,
                    "Merged PR, but failed to remove the clean worktree: " + processDetail(remove));
            return;
        }
        ProcessResult prune = processRunner.run(
                List.of("git", "-C", repoDir.getAbsolutePath(), "worktree", "prune"),
                repoDir,
                GIT_TIMEOUT);
        if (prune.timedOut() || prune.exitCode() != 0) {
            annotateBestEffort(taskId, actorId,
                    "Merged worktree was removed, but git worktree prune failed: " + processDetail(prune));
            return;
        }
        annotateBestEffort(taskId, actorId,
                "Merged worktree removed and pruned: " + worktreeDir.getAbsolutePath());
    }

    public void cleanupWorktreeAndBranch(File repoDir, File worktreeDir, String branchName) {
        if (repoDir == null || worktreeDir == null) {
            return;
        }
        ProcessResult remove = processRunner.run(
                List.of("git", "-C", repoDir.getAbsolutePath(), "worktree", "remove",
                        worktreeDir.getAbsolutePath(), "--force"),
                repoDir,
                GIT_TIMEOUT);
        if (remove.exitCode() != 0 || remove.timedOut()) {
            log.warn("Unable to remove worktree {} during cleanup: {}", worktreeDir, processDetail(remove));
        }
        if (branchName != null && !branchName.isBlank()) {
            ProcessResult deleteBranch = processRunner.run(
                    List.of("git", "-C", repoDir.getAbsolutePath(), "branch", "-D", branchName),
                    repoDir,
                    GIT_TIMEOUT);
            if (deleteBranch.exitCode() != 0 || deleteBranch.timedOut()) {
                log.warn("Unable to delete branch {} during cleanup: {}", branchName, processDetail(deleteBranch));
            }
        }
    }

    private void block(String taskId, String actorId, String reason) {
        apiClient.updateTaskStatus(taskId, actorId, "blocked", reason);
    }

    private void annotateBestEffort(String taskId, String actorId, String text) {
        try {
            apiClient.annotate(taskId, actorId, "progress", text, null);
        }
        catch (RuntimeException ex) {
            log.warn("Unable to annotate post-merge worktree state for task {}", taskId, ex);
        }
    }

    private static String processDetail(ProcessResult result) {
        String output = result.stderr() != null && !result.stderr().isBlank()
                ? result.stderr().strip()
                : safeStrip(result.stdout());
        return "exit " + result.exitCode()
                + (result.timedOut() ? ", timed out" : "")
                + (output.isBlank() ? "" : ": " + output);
    }

    private static String safeStrip(String value) {
        return value == null ? "" : value.strip();
    }

    public record GitState(String head, String status) {
    }
}
