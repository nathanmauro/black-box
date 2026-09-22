package dev.nathan.sbaagentic.runner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import dev.nathan.sbaagentic.runner.internal.application.WorktreeManager;
import dev.nathan.sbaagentic.runner.internal.application.WorktreeManager.CreatedWorktree;
import dev.nathan.sbaagentic.runner.internal.client.blackbox.BlackBoxApiClient;
import dev.nathan.sbaagentic.runner.process.ProcessRunner;
import dev.nathan.sbaagentic.runner.process.ProcessRunner.ProcessResult;
import dev.nathan.sbaagentic.runner.process.RealProcessRunner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorktreeManagerTest {

    @TempDir
    Path tempDir;

    private final RealProcessRunner git = new RealProcessRunner();

    @Test
    void removesOnlyUnchangedOwnedWorktreeAndBranch() throws Exception {
        Fixture fixture = fixture();
        assertThat(fixture.manager().cleanupWorktreeAndBranch(fixture.created()))
                .contains("no worker output");
        assertThat(fixture.worktree()).doesNotExist();
        assertThat(run(fixture.repo(), "branch", "--list", "auto/test").stdout())
                .isBlank();
    }

    @Test
    void preservesCleanWorktreeWithUniqueCommit() throws Exception {
        Fixture fixture = fixture();
        runOk(fixture.worktree(), "commit", "--allow-empty", "-m", "unique work");
        String head = run(fixture.worktree(), "rev-parse", "HEAD").stdout();
        assertThat(fixture.manager().cleanupWorktreeAndBranch(fixture.created()))
                .contains("HEAD differs");
        assertThat(fixture.worktree()).isDirectory();
        assertThat(run(fixture.repo(), "rev-parse", "auto/test").stdout()).isEqualTo(head);
    }

    @Test
    void preservesTrackedUntrackedAndIgnoredOutput() throws Exception {
        Fixture fixture = fixture();
        Files.writeString(fixture.worktree().resolve("README.md"), "tracked edit\n");
        assertThat(fixture.manager().cleanupWorktreeAndBranch(fixture.created()))
                .contains("files changed");
        runOk(fixture.worktree(), "restore", "README.md");
        Files.writeString(fixture.worktree().resolve("notes.txt"), "untracked output\n");
        assertThat(fixture.manager().cleanupWorktreeAndBranch(fixture.created()))
                .contains("files changed");
        Files.delete(fixture.worktree().resolve("notes.txt"));
        Files.writeString(fixture.worktree().resolve("worker.log"), "ignored evidence\n");
        assertThat(run(fixture.worktree(), "status", "--porcelain").stdout()).isBlank();
        assertThat(fixture.manager().cleanupWorktreeAndBranch(fixture.created()))
                .contains("files changed");
        assertThat(Files.readString(fixture.worktree().resolve("worker.log"))).isEqualTo("ignored evidence\n");
    }

    @Test
    void preservesUnknownOwnershipAndChangedBranch() throws Exception {
        Fixture fixture = fixture();
        assertThat(fixture.manager().cleanupWorktreeAndBranch(null)).contains("no verified worktree ownership");
        runOk(fixture.worktree(), "switch", "-c", "someone-elses-work");
        assertThat(fixture.manager().cleanupWorktreeAndBranch(fixture.created()))
                .contains("ownership no longer matches");
        assertThat(fixture.worktree()).isDirectory();
        assertThat(run(fixture.repo(), "branch", "--list", "auto/test").stdout())
                .isNotBlank();
    }

    @Test
    void preservesForeignRepositoryEvenWithMatchingBranchAndCommit() throws Exception {
        Fixture fixture = fixture();
        Path foreign = tempDir.resolve("foreign");
        runOk(tempDir, "clone", fixture.repo().toString(), foreign.toString());
        CreatedWorktree wrongRepo = new CreatedWorktree(
                foreign.toRealPath(),
                fixture.created().worktree(),
                fixture.created().branch(),
                fixture.created().initialHead(),
                fixture.created().gitDirectory());
        assertThat(fixture.manager().cleanupWorktreeAndBranch(wrongRepo)).contains("ownership no longer matches");
        assertThat(fixture.worktree()).isDirectory();
    }

    @Test
    void preservesOnFailedOrTimedOutProbe() throws Exception {
        Fixture fixture = fixture();
        for (boolean timeout : List.of(false, true)) {
            ProcessRunner failedProbe = (command, cwd, duration) -> command.contains("status")
                    ? new ProcessResult(-1, "", "controlled probe failure", timeout)
                    : git.run(command, cwd, duration);
            WorktreeManager manager = new WorktreeManager(mock(BlackBoxApiClient.class), failedProbe);
            assertThat(manager.cleanupWorktreeAndBranch(fixture.created())).contains("requires inspection");
            assertThat(fixture.worktree()).isDirectory();
        }
    }

    @Test
    void preservesWhenRealGitProbeIsInterrupted() throws Exception {
        Fixture fixture = fixture();
        Thread.currentThread().interrupt();
        try {
            assertThat(fixture.manager().cleanupWorktreeAndBranch(fixture.created()))
                    .contains("requires inspection");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
        assertThat(fixture.worktree()).isDirectory();
        assertThat(run(fixture.repo(), "branch", "--list", "auto/test").stdout())
                .isNotBlank();
    }

    @Test
    void nonForcedRemovalRefusesFilesWrittenAfterCleanProbe() throws Exception {
        Fixture fixture = fixture();
        ProcessRunner racingWriter = (command, cwd, duration) -> {
            if (command.contains("remove")) {
                try {
                    Files.writeString(fixture.worktree().resolve("late-output.txt"), "late worker output\n");
                } catch (Exception ex) {
                    throw new IllegalStateException(ex);
                }
            }

            return git.run(command, cwd, duration);
        };
        WorktreeManager manager = new WorktreeManager(mock(BlackBoxApiClient.class), racingWriter);
        assertThat(manager.cleanupWorktreeAndBranch(fixture.created())).contains("branch retained");
        assertThat(Files.readString(fixture.worktree().resolve("late-output.txt")))
                .isEqualTo("late worker output\n");
    }

    @Test
    void conditionalBranchDeletionPreservesCommitWrittenAfterWorktreeRemoval() throws Exception {
        Fixture fixture = fixture();
        String tree = run(fixture.repo(), "rev-parse", "HEAD^{tree}").stdout().strip();
        ProcessResult commit = run(fixture.repo(), "commit-tree", tree, "-p", "HEAD", "-m", "racing commit");
        assertThat(commit.exitCode()).isZero();
        String newHead = commit.stdout().strip();
        AtomicBoolean wrote = new AtomicBoolean();
        ProcessRunner racingWriter = (command, cwd, duration) -> {
            if (command.contains("update-ref") && command.contains("-d") && wrote.compareAndSet(false, true)) {
                runOk(fixture.repo(), "update-ref", "refs/heads/auto/test", newHead);
            }

            return git.run(command, cwd, duration);
        };
        WorktreeManager manager = new WorktreeManager(mock(BlackBoxApiClient.class), racingWriter);
        assertThat(manager.cleanupWorktreeAndBranch(fixture.created())).contains("branch deletion not confirmed");
        assertThat(run(fixture.repo(), "rev-parse", "auto/test").stdout().strip())
                .isEqualTo(newHead);
    }

    private Fixture fixture() throws Exception {
        Path repo = Files.createDirectories(tempDir.resolve("repo"));
        runOk(repo, "init");
        runOk(repo, "config", "user.name", "Nathan");
        runOk(repo, "config", "user.email", "nathan@example.test");
        Files.writeString(repo.resolve("README.md"), "fixture\n");
        Files.writeString(repo.resolve(".gitignore"), "*.log\n");
        runOk(repo, "add", ".");
        runOk(repo, "commit", "-m", "initial fixture");
        Path worktree = tempDir.resolve("worktree");
        WorktreeManager manager = new WorktreeManager(mock(BlackBoxApiClient.class), git);
        assertThat(manager.createWorktree(repo.toFile(), worktree.toFile(), "auto/test")
                        .exitCode())
                .isZero();
        CreatedWorktree created = manager.createdWorktree(repo.toFile(), worktree.toFile(), "auto/test");
        assertThat(created).isNotNull();

        return new Fixture(repo, worktree, manager, created);
    }

    private ProcessResult run(Path directory, String... args) {
        java.util.ArrayList<String> command = new java.util.ArrayList<>(List.of("git", "-C", directory.toString()));
        command.addAll(List.of(args));

        return git.run(command, directory.toFile(), Duration.ofSeconds(10));
    }

    private void runOk(Path directory, String... args) {
        ProcessResult result = run(directory, args);
        assertThat(result.exitCode()).as(result.stderr()).isZero();
    }

    private record Fixture(Path repo, Path worktree, WorktreeManager manager, CreatedWorktree created) {}
}
