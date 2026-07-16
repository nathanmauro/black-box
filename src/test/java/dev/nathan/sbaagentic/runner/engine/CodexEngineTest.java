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
    void dashLeadingPromptIsNeverParsedAsFlag() {
        List<String> command = engine.command(
                "--shout looks like a flag",
                new EngineConfig("codex", "gpt-5.6-sol", "xhigh", null, null, true));

        assertThat(command.subList(0, command.size() - 1)).doesNotContain("--shout looks like a flag");
        assertThat(command.get(command.size() - 2)).isEqualTo("--");
        assertThat(command.get(command.size() - 1)).isEqualTo("--shout looks like a flag");
    }
}
