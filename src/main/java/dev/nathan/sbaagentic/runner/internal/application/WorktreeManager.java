package dev.nathan.sbaagentic.runner.internal.application;

import dev.nathan.sbaagentic.runner.RunnerNaming;
import dev.nathan.sbaagentic.runner.internal.client.blackbox.BlackBoxApiClient;
import dev.nathan.sbaagentic.runner.internal.client.blackbox.Task;
import dev.nathan.sbaagentic.runner.process.ProcessRunner;
import dev.nathan.sbaagentic.runner.process.ProcessRunner.ProcessResult;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

    public boolean prepareWorktree(Task task, String actorId, File repoDir, File worktreeDir, String branchName) {
        File worktreeParent = worktreeDir.getParentFile();
        if (worktreeParent != null && !worktreeParent.isDirectory() && !worktreeParent.mkdirs()) {
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
                List.of("git", "-C", repoDir.getAbsolutePath(), "rev-parse", "--abbrev-ref", "origin/HEAD"),
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
        } else {
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
                        "git",
                        "-C",
                        repoDir.getAbsolutePath(),
                        "worktree",
                        "add",
                        worktreeDir.getAbsolutePath(),
                        "-b",
                        branchName,
                        defaultBranch),
                repoDir,
                GIT_TIMEOUT);
    }

    public static String branchName(String title, String taskId) {
        String slug = title == null
                ? ""
                : title.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (slug.length() > 40) {
            slug = slug.substring(0, 40).replaceAll("-+$", "");
        }
        if (slug.isBlank()) {
            slug = "task";
        }

        return "auto/" + slug + "-" + RunnerNaming.taskShort(taskId);
    }

    public boolean validPreservedWorktree(File repoDir, Task buildTask, String artifactBranch, File artifactWorktree) {
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
        if (branch.timedOut() || branch.exitCode() != 0 || !artifactBranch.equals(safeStrip(branch.stdout()))) {

            return false;
        }
        ProcessResult status = processRunner.run(
                List.of("git", "-C", artifactWorktree.getAbsolutePath(), "status", "--porcelain"),
                artifactWorktree,
                GIT_TIMEOUT);

        return !status.timedOut()
                && status.exitCode() == 0
                && safeStrip(status.stdout()).isBlank();
    }

    public GitState gitState(File worktreeDir) {
        ProcessResult head = processRunner.run(
                List.of("git", "-C", worktreeDir.getAbsolutePath(), "rev-parse", "HEAD"), worktreeDir, GIT_TIMEOUT);
        ProcessResult status = processRunner.run(
                List.of("git", "-C", worktreeDir.getAbsolutePath(), "status", "--porcelain"), worktreeDir, GIT_TIMEOUT);
        if (head.timedOut() || head.exitCode() != 0 || status.timedOut() || status.exitCode() != 0) {
            throw new IllegalStateException("Unable to verify stage worktree state: head=" + processDetail(head)
                    + ", status=" + processDetail(status));
        }

        return new GitState(safeStrip(head.stdout()), status.stdout() == null ? "" : status.stdout());
    }

    public void pruneMergedWorktree(String taskId, String actorId, File repoDir, File worktreeDir) {
        ProcessResult status = processRunner.run(
                List.of("git", "-C", worktreeDir.getAbsolutePath(), "status", "--porcelain"), worktreeDir, GIT_TIMEOUT);
        if (status.timedOut() || status.exitCode() != 0) {
            annotateBestEffort(
                    taskId,
                    actorId,
                    "Merged PR, but left the worktree in place because cleanliness could not be verified: "
                            + processDetail(status));

            return;
        }
        if (!status.stdout().isBlank()) {
            annotateBestEffort(
                    taskId,
                    actorId,
                    "Merged PR, but left the dirty worktree in place: " + worktreeDir.getAbsolutePath());

            return;
        }
        ProcessResult remove = processRunner.run(
                List.of(
                        "git",
                        "-C",
                        repoDir.getAbsolutePath(),
                        "worktree",
                        "remove",
                        worktreeDir.getAbsolutePath(),
                        "--force"),
                repoDir,
                GIT_TIMEOUT);
        if (remove.timedOut() || remove.exitCode() != 0) {
            annotateBestEffort(
                    taskId, actorId, "Merged PR, but failed to remove the clean worktree: " + processDetail(remove));

            return;
        }
        ProcessResult prune = processRunner.run(
                List.of("git", "-C", repoDir.getAbsolutePath(), "worktree", "prune"), repoDir, GIT_TIMEOUT);
        if (prune.timedOut() || prune.exitCode() != 0) {
            annotateBestEffort(
                    taskId,
                    actorId,
                    "Merged worktree was removed, but git worktree prune failed: " + processDetail(prune));

            return;
        }
        annotateBestEffort(taskId, actorId, "Merged worktree removed and pruned: " + worktreeDir.getAbsolutePath());
    }

    /** Capture only after this run successfully creates the worktree, before launching a worker. */
    public CreatedWorktree createdWorktree(File repoDir, File worktreeDir, String branchName) {
        try {
            String head = probe(worktreeDir, "rev-parse", "HEAD");
            if (!head.matches("[0-9a-f]{40}|[0-9a-f]{64}")) {
                throw new IllegalStateException("Initial HEAD is not a commit ID");
            }

            return new CreatedWorktree(
                    realPath(repoDir.toPath()),
                    realPath(worktreeDir.toPath()),
                    branchName,
                    head,
                    realPath(Path.of(probe(worktreeDir, "rev-parse", "--absolute-git-dir"))));
        } catch (RuntimeException ex) {
            log.warn("Unable to capture ownership of created worktree {}; automatic cleanup disabled", worktreeDir, ex);

            return null;
        }
    }

    /** Remove only an unchanged checkout created by this run. Unknown state always preserves. */
    public String cleanupWorktreeAndBranch(CreatedWorktree created) {
        if (created == null) {

            return "Cleanup skipped: this run has no verified worktree ownership.";
        }
        try {
            File repo = created.repo().toFile();
            File worktree = created.worktree().toFile();
            if (!created.worktree().equals(realPath(Path.of(probe(worktree, "rev-parse", "--show-toplevel"))))
                    || !created.gitDirectory()
                            .equals(realPath(Path.of(probe(worktree, "rev-parse", "--absolute-git-dir"))))
                    || !realPath(Path.of(probe(repo, "rev-parse", "--path-format=absolute", "--git-common-dir")))
                            .equals(realPath(Path.of(
                                    probe(worktree, "rev-parse", "--path-format=absolute", "--git-common-dir"))))
                    || !created.branch().equals(probe(worktree, "symbolic-ref", "--short", "HEAD"))) {

                return "Worktree and branch preserved: ownership no longer matches this run.";
            }
            if (!created.initialHead().equals(probe(worktree, "rev-parse", "HEAD"))) {

                return "Worktree and branch preserved: HEAD differs from the initial commit.";
            }
            // Include ignored output (such as worker logs) as well as tracked and untracked files.
            if (!probe(worktree, "status", "--porcelain=v1", "--untracked-files=all", "--ignored=matching")
                    .isBlank()) {

                return "Worktree and branch preserved: tracked, untracked, or ignored files changed.";
            }
            ProcessResult remove = processRunner.run(
                    List.of("git", "-C", repo.getAbsolutePath(), "worktree", "remove", worktree.getAbsolutePath()),
                    repo,
                    GIT_TIMEOUT);
            if (remove.exitCode() != 0 || remove.timedOut()) {

                return "Cleanup could not confirm worktree removal; branch retained: " + processDetail(remove);
            }
            // Compare-and-delete protects a branch that advanced after the probes. Never force-delete.
            ProcessResult delete = processRunner.run(
                    List.of(
                            "git",
                            "-C",
                            repo.getAbsolutePath(),
                            "update-ref",
                            "-d",
                            "refs/heads/" + created.branch(),
                            created.initialHead()),
                    repo,
                    GIT_TIMEOUT);
            if (delete.exitCode() != 0 || delete.timedOut()) {

                return "Unchanged worktree removed; branch deletion not confirmed (inspect ref): "
                        + processDetail(delete);
            }

            return "Unchanged worktree and branch removed; no worker output was found.";
        } catch (RuntimeException ex) {
            log.warn(
                    "Cleanup stopped for worktree {} and branch {}; inspect recovery state",
                    created.worktree(),
                    created.branch(),
                    ex);

            return "Cleanup stopped; worktree/branch state requires inspection: " + ex.getMessage();
        }
    }

    public String reportRecovery(
            String taskId,
            String actorId,
            String runId,
            File worktree,
            String branch,
            String checkpoint,
            String disposition) {
        String text = "Task " + taskId + "; run " + runId
                + "; recovery worktree: " + (worktree == null ? "not allocated" : worktree.getAbsolutePath())
                + "; branch: " + (branch == null ? "not allocated" : branch)
                + "; last verified checkpoint: " + checkpoint + ". " + disposition;
        // Keep the recovery pointer in runner logs even when the API is unavailable.
        log.warn("{}", text);
        try {
            apiClient.annotate(taskId, actorId, "progress", text, Map.of("event", "run_recovery"));
        } catch (RuntimeException ex) {
            log.warn("Unable to annotate recovery state for task {}; see runner log above", taskId, ex);
        }

        return text;
    }

    private String probe(File directory, String... arguments) {
        java.util.ArrayList<String> command =
                new java.util.ArrayList<>(List.of("git", "-C", directory.getAbsolutePath()));
        command.addAll(List.of(arguments));
        ProcessResult result = processRunner.run(command, directory, GIT_TIMEOUT);
        if (result.timedOut() || result.exitCode() != 0 || result.stdout() == null) {
            throw new IllegalStateException("Unable to inspect worktree: " + processDetail(result));
        }

        return result.stdout().strip();
    }

    private static Path realPath(Path path) {
        try {

            return path.toRealPath();
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to resolve worktree identity: " + path, ex);
        }
    }

    public record CreatedWorktree(Path repo, Path worktree, String branch, String initialHead, Path gitDirectory) {}

    private void block(String taskId, String actorId, String reason) {
        apiClient.updateTaskStatus(taskId, actorId, "blocked", reason);
    }

    private void annotateBestEffort(String taskId, String actorId, String text) {
        try {
            apiClient.annotate(taskId, actorId, "progress", text, null);
        } catch (RuntimeException ex) {
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

    public record GitState(String head, String status) {}
}
