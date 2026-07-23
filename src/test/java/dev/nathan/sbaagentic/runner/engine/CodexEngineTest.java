package dev.nathan.sbaagentic.runner.engine;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

import dev.nathan.sbaagentic.runner.EngineConfig;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class CodexEngineTest {

    private final CodexEngine engine = new CodexEngine();

    @Test
    void passesPromptAfterEndOfOptionsMarker() {
        List<String> command = engine.command(
                "---\nstory: v1\n---\n# Title",
                new EngineConfig("codex", "gpt-5.6-sol", "ultra", "workspace-write", null, true),
                null);

        int marker = command.indexOf("--");
        assertThat(marker).isGreaterThan(0);
        assertThat(command).endsWith("--", "---\nstory: v1\n---\n# Title");
    }

    @Test
    void workspaceWriteSandboxAllowsLoopbackReporting() {
        List<String> command = engine.command(
                "prompt",
                new EngineConfig("codex", "gpt-5.6-sol", "ultra", "workspace-write", null, true),
                null);

        assertThat(command).containsSequence(
                "--sandbox", "workspace-write", "-c", "sandbox_workspace_write.network_access=true");
    }

    @Test
    void otherSandboxModesGetNoNetworkOverride() {
        List<String> command = engine.command(
                "prompt",
                new EngineConfig("codex", "gpt-5.6-sol", "ultra", "read-only", null, true),
                null);

        assertThat(command).doesNotContain("sandbox_workspace_write.network_access=true");
    }

    @Test
    void linkedWorktreeGitCommonDirBecomesWritableRoot(@TempDir File repo) throws IOException {
        File worktree = new File(repo, ".worktrees/bb-12345678");
        File gitDir = new File(repo, ".git/worktrees/bb-12345678");
        assertThat(gitDir.mkdirs()).isTrue();
        assertThat(worktree.mkdirs()).isTrue();
        Files.writeString(new File(worktree, ".git").toPath(),
                "gitdir: " + gitDir.getAbsolutePath() + "\n");

        List<String> command = engine.command(
                "prompt",
                new EngineConfig("codex", "gpt-5.6-sol", "ultra", "workspace-write", null, true),
                worktree);

        assertThat(command).containsSequence(
                "-c",
                "sandbox_workspace_write.writable_roots=[\""
                        + new File(repo, ".git").getAbsolutePath() + "\"]");
    }

    @Test
    void regularCheckoutGetsNoWritableRootOverride(@TempDir File repo) {
        List<String> command = engine.command(
                "prompt",
                new EngineConfig("codex", "gpt-5.6-sol", "ultra", "workspace-write", null, true),
                repo);

        assertThat(command).doesNotContain("-c sandbox_workspace_write.writable_roots");
        assertThat(String.join(" ", command)).doesNotContain("writable_roots");
    }

    @Test
    void worktreeDirIsPreTrustedBeforeEndOfOptionsMarker(@TempDir File worktree) throws IOException {
        List<String> command = engine.command(
                "prompt",
                new EngineConfig("codex", "gpt-5.6-sol", "ultra", "workspace-write", null, true),
                worktree);

        String override = projectsOverride(command);
        assertThat(override).contains(
                "\"" + worktree.getCanonicalPath() + "\"={trust_level=\"trusted\"}");
        assertThat(command.indexOf(override)).isLessThan(command.indexOf("--"));
    }

    @Test
    void symlinkedWorktreeEmitsBothRawAndPhysicalTrustEntries(@TempDir File tempDir) throws IOException {
        File real = new File(tempDir, "real");
        assertThat(real.mkdir()).isTrue();
        File link = new File(tempDir, "link");
        Files.createSymbolicLink(link.toPath(), real.toPath());

        String override = projectsOverride(engine.command(
                "prompt",
                new EngineConfig("codex", "gpt-5.6-sol", "ultra", "workspace-write", null, true),
                link));

        assertThat(override).contains("\"" + link.getAbsolutePath() + "\"={trust_level=\"trusted\"}");
        assertThat(override).contains("\"" + link.getCanonicalPath() + "\"={trust_level=\"trusted\"}");
    }

    @Test
    void quotesInWorktreePathAreTomlEscaped(@TempDir File tempDir) {
        File weird = new File(tempDir, "we\"ird");

        String override = projectsOverride(engine.command(
                "prompt",
                new EngineConfig("codex", "gpt-5.6-sol", "ultra", "workspace-write", null, true),
                weird));

        assertThat(override).contains("we\\\"ird");
    }

    @Test
    void nullWorktreeGetsNoTrustOverride() {
        List<String> command = engine.command(
                "prompt",
                new EngineConfig("codex", "gpt-5.6-sol", "ultra", null, null, true),
                null);

        assertThat(String.join(" ", command)).doesNotContain("projects=");
    }

    private static String projectsOverride(List<String> command) {
        return command.stream()
                .filter(arg -> arg.startsWith("projects={"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no projects override in " + command));
    }

    @Test
    void dashLeadingPromptIsNeverParsedAsFlag() {
        List<String> command = engine.command(
                "--shout looks like a flag",
                new EngineConfig("codex", "gpt-5.6-sol", "xhigh", null, null, true),
                null);

        assertThat(command.subList(0, command.size() - 1)).doesNotContain("--shout looks like a flag");
        assertThat(command.get(command.size() - 2)).isEqualTo("--");
        assertThat(command.get(command.size() - 1)).isEqualTo("--shout looks like a flag");
    }
}
