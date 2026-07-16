package dev.nathan.sbaagentic.runner.ship;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nathan.sbaagentic.runner.RunnerNaming;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class ShipScriptTest {

    @TempDir
    Path tempDir;

    private Path fakeBin;

    @BeforeEach
    void setUp() throws Exception {
        fakeBin = Files.createDirectory(tempDir.resolve("bin"));
        Path fakeGh = fakeBin.resolve("gh");
        Files.writeString(fakeGh, "#!/bin/sh\nexit 1\n");
        assertThat(fakeGh.toFile().setExecutable(true)).isTrue();
    }

    @Test
    void anyNonEmptyDangerValueFailsClosed() throws Exception {
        Path repo = Files.createDirectory(tempDir.resolve("repo"));

        JsonNode result = runShip(repo, "auto/story-task1", "requires human review");

        assertThat(result.path("status").asText()).isEqualTo("local-only");
        assertThat(result.path("reason").asText())
                .isEqualTo("repo config danger flag: requires human review");
        assertThat(result.path("manualCommands")).hasSize(2);
    }

    @Test
    void pushFailureReturnsPushAndPrManualCommands() throws Exception {
        Path repo = initializeRepo();
        String branch = command(repo, "git", "branch", "--show-current").strip();
        command(repo, "git", "remote", "add", "origin", tempDir.resolve("missing.git").toString());

        JsonNode result = runShip(repo, branch, "");

        assertThat(result.path("status").asText()).isEqualTo("local-only");
        assertThat(result.path("reason").asText()).startsWith("git push failed:");
        assertThat(result.path("manualCommands")).hasSize(2);
        assertThat(result.at("/manualCommands/0").asText()).contains("git", "push -u origin");
        assertThat(result.at("/manualCommands/1").asText()).contains("gh pr create");
    }

    private JsonNode runShip(Path repo, String branch, String danger) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(List.of(
                "/bin/bash",
                RunnerNaming.scriptPath("scripts/runner/ship.sh"),
                "task-1",
                repo.toString(),
                branch,
                repo.toString(),
                "true",
                "true",
                danger,
                "Story title",
                "Worker summary"));
        builder.directory(repo.toFile());
        builder.environment().put(
                "PATH",
                fakeBin + File.pathSeparator + System.getenv().getOrDefault("PATH", ""));
        Process process = builder.start();
        boolean finished = process.waitFor(10, TimeUnit.SECONDS);
        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);

        assertThat(finished).as("ship.sh timed out; stderr: %s", stderr).isTrue();
        assertThat(process.exitValue()).as("ship.sh stderr: %s", stderr).isZero();
        return new ObjectMapper().readTree(stdout.lines()
                .filter(line -> !line.isBlank())
                .reduce((first, second) -> second)
                .orElseThrow());
    }

    private Path initializeRepo() throws Exception {
        Path repo = Files.createDirectory(tempDir.resolve("repo"));
        command(repo, "git", "init");
        command(repo, "git", "config", "user.name", "Nathan");
        command(repo, "git", "config", "user.email", "nathan@example.test");
        Files.writeString(repo.resolve("README.md"), "fixture\n");
        command(repo, "git", "add", "README.md");
        command(repo, "git", "commit", "-m", "initial fixture");
        return repo;
    }

    private static String command(Path cwd, String... command) throws Exception {
        Process process = new ProcessBuilder(command).directory(cwd.toFile()).start();
        boolean finished = process.waitFor(10, TimeUnit.SECONDS);
        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(finished).as("command timed out: %s", List.of(command)).isTrue();
        assertThat(process.exitValue())
                .as("command failed: %s; stderr: %s", List.of(command), stderr)
                .isZero();
        return stdout;
    }
}
