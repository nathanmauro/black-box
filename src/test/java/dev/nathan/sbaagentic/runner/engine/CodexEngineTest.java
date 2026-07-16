package dev.nathan.sbaagentic.runner.engine;

import java.util.List;

import dev.nathan.sbaagentic.runner.EngineConfig;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CodexEngineTest {

    private final CodexEngine engine = new CodexEngine();

    @Test
    void passesPromptAfterEndOfOptionsMarker() {
        List<String> command = engine.command(
                "---\nstory: v1\n---\n# Title",
                new EngineConfig("codex", "gpt-5.6-sol", "ultra", "workspace-write", null, true));

        int marker = command.indexOf("--");
        assertThat(marker).isGreaterThan(0);
        assertThat(command).endsWith("--", "---\nstory: v1\n---\n# Title");
    }

    @Test
    void workspaceWriteSandboxAllowsLoopbackReporting() {
        List<String> command = engine.command(
                "prompt",
                new EngineConfig("codex", "gpt-5.6-sol", "ultra", "workspace-write", null, true));

        assertThat(command).containsSequence(
                "--sandbox", "workspace-write", "-c", "sandbox_workspace_write.network_access=true");
    }

    @Test
    void otherSandboxModesGetNoNetworkOverride() {
        List<String> command = engine.command(
                "prompt",
                new EngineConfig("codex", "gpt-5.6-sol", "ultra", "read-only", null, true));

        assertThat(command).doesNotContain("sandbox_workspace_write.network_access=true");
    }

    @Test
    void dashLeadingPromptIsNeverParsedAsFlag() {
        List<String> command = engine.command(
                "--shout looks like a flag",
                new EngineConfig("codex", "gpt-5.6-sol", "xhigh", null, null, true));

        assertThat(command.subList(0, command.size() - 1)).doesNotContain("--shout looks like a flag");
        assertThat(command.get(command.size() - 2)).isEqualTo("--");
        assertThat(command.get(command.size() - 1)).isEqualTo("--shout looks like a flag");
    }
}
