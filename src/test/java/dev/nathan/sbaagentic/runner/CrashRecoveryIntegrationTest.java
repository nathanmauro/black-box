package dev.nathan.sbaagentic.runner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.nathan.sbaagentic.runner.gate.StoryFrontmatterParser;
import dev.nathan.sbaagentic.runner.internal.client.blackbox.BlackBoxApiClient;
import dev.nathan.sbaagentic.runner.process.ProcessRunner.ProcessResult;
import dev.nathan.sbaagentic.runner.process.RealProcessRunner;
import dev.nathan.sbaagentic.runner.process.TmuxController;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CrashRecoveryIntegrationTest {

    @TempDir
    Path tempDir;

    private final RealProcessRunner processRunner = new RealProcessRunner();

    @Test
    void restartPreservesCleanOrphanWhenWorkerIsAliveOrSessionProbeFails() throws Exception {
        for (boolean unknown : List.of(false, true)) {
            Path repo = Files.createDirectories(tempDir.resolve("worker-" + unknown));
            git(repo, "init", "--initial-branch=main");
            git(repo, "config", "user.name", "Nathan");
            git(repo, "config", "user.email", "nathan@example.test");
            git(repo, "commit", "--allow-empty", "-m", "initial fixture");
            Path worktree = repo.resolve(".worktrees/bb-orphan");
            git(repo, "worktree", "add", "-b", "auto/orphan", worktree.toString(), "main");
            TmuxController tmux = mock(TmuxController.class);
            if (unknown)
                when(tmux.hasSession("bb-run-orphan"))
                        .thenThrow(new IllegalStateException("session probe unavailable"));
            else when(tmux.hasSession("bb-run-orphan")).thenReturn(true);

            new CrashRecovery(mock(BlackBoxApiClient.class), tmux, processRunner, new StoryFrontmatterParser())
                    .reconcile(
                            new RunnerConfig(
                                    1,
                                    List.of(),
                                    null,
                                    List.of(new RepoConfig(repo.toString(), false, false, "git status --short", ""))),
                            "blackbox-runner");

            assertThat(worktree).isDirectory();
            assertThat(git(repo, "branch", "--list", "auto/orphan").stdout()).isNotBlank();
        }
    }

    @Test
    void restartPreservesIgnoredOnlyWorkerEvidenceAndStillCleansEmptyOrphans() throws Exception {
        for (boolean hasLog : List.of(true, false)) {
            Path repo = Files.createDirectories(tempDir.resolve("repo-" + hasLog));
            git(repo, "init", "--initial-branch=main");
            git(repo, "config", "user.name", "Nathan");
            git(repo, "config", "user.email", "nathan@example.test");
            Files.writeString(repo.resolve(".gitignore"), ".worktrees/\n*.log\n");
            git(repo, "add", ".gitignore");
            git(repo, "commit", "-m", "initial fixture");
            Path worktree = repo.resolve(".worktrees/bb-orphan");
            git(repo, "worktree", "add", "-b", "auto/orphan", worktree.toString(), "main");
            if (hasLog) Files.writeString(worktree.resolve("worker.log"), "last verified worker checkpoint\n");
            assertThat(git(worktree, "status", "--porcelain").stdout()).isBlank();

            new CrashRecovery(
                            mock(BlackBoxApiClient.class),
                            mock(TmuxController.class),
                            processRunner,
                            new StoryFrontmatterParser())
                    .reconcile(
                            new RunnerConfig(
                                    1,
                                    List.of(),
                                    null,
                                    List.of(new RepoConfig(repo.toString(), false, false, "git status --short", ""))),
                            "blackbox-runner");

            if (hasLog) {
                assertThat(Files.readString(worktree.resolve("worker.log")))
                        .isEqualTo("last verified worker checkpoint\n");
                assertThat(git(repo, "branch", "--list", "auto/orphan").stdout())
                        .isNotBlank();
            } else {
                assertThat(worktree).doesNotExist();
                assertThat(git(repo, "branch", "--list", "auto/orphan").stdout())
                        .isBlank();
            }
        }
    }

    private ProcessResult git(Path directory, String... arguments) {
        List<String> command = new ArrayList<>(List.of("git", "-C", directory.toString()));
        command.addAll(List.of(arguments));
        ProcessResult result = processRunner.run(command, directory.toFile(), Duration.ofSeconds(10));
        assertThat(result.exitCode()).as(result.stderr()).isZero();

        return result;
    }
}
