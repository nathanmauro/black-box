package dev.nathan.sbaagentic.platform.internal.adapter.in.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClientOnboardingPlannerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ClientOnboardingPlanner planner = new ClientOnboardingPlanner(mapper);

    @TempDir
    Path tempDir;

    @Test
    void dryRunPreviewsTargetsWithoutCreatingFiles() throws Exception {
        Path home = tempDir.resolve("home");
        Path repo = tempDir.resolve("repo");
        Files.createDirectories(home);
        Files.createDirectories(repo);

        var report = planner.plan(home, repo, "http://127.0.0.1:8766/", false);

        assertThat(report.mode()).isEqualTo("dry-run");
        assertThat(report.applied()).isFalse();
        assertThat(report.mcpEndpoint()).isEqualTo("http://127.0.0.1:8766/mcp");
        assertThat(report.mcpCommands())
                .containsEntry(
                        "claude", "claude mcp add --transport http --scope user black-box http://127.0.0.1:8766/mcp")
                .containsEntry("codex", "codex mcp add black-box --url http://127.0.0.1:8766/mcp");
        assertThat(report.targets())
                .extracting(ClientOnboardingPlanner.TargetResult::status)
                .containsExactly("would_create", "would_create", "would_create");
        assertThat(Files.exists(home.resolve(".claude/settings.json"))).isFalse();
        assertThat(Files.exists(home.resolve(".codex/hooks.json"))).isFalse();
        assertThat(Files.exists(repo.resolve(".claude/skills/black-box/SKILL.md")))
                .isFalse();
    }

    @Test
    void applyCreatesAndIdempotentlyUpdatesConfigFilesWhilePreservingExistingSettings() throws Exception {
        Path home = tempDir.resolve("home");
        Path repo = tempDir.resolve("repo");
        Path claudeSettings = home.resolve(".claude/settings.json");
        Files.createDirectories(claudeSettings.getParent());
        Files.createDirectories(repo);
        Files.writeString(claudeSettings, """
                {
                  "theme": "dark",
                  "hooks": {
                    "SessionStart": [
                      {
                        "matcher": "startup",
                        "hooks": [
                          { "type": "command", "command": "echo existing" }
                        ]
                      }
                    ]
                  }
                }
                """, StandardCharsets.UTF_8);

        var first = planner.plan(home, repo, "http://127.0.0.1:8766", true);

        assertThat(first.mode()).isEqualTo("applied");
        assertThat(first.applied()).isTrue();
        assertThat(first.targets())
                .extracting(ClientOnboardingPlanner.TargetResult::status)
                .containsExactly("updated", "created", "created");

        JsonNode parsedClaude = mapper.readTree(claudeSettings.toFile());
        assertThat(parsedClaude.path("theme").asText()).isEqualTo("dark");
        assertThat(parsedClaude.path("hooks").path("SessionStart")).hasSize(2);
        assertThat(parsedClaude.path("hooks").path("SubagentStart")).hasSize(1);
        assertThat(parsedClaude.path("hooks").path("SubagentStop")).hasSize(1);
        assertThat(Files.readString(repo.resolve(".claude/skills/black-box/SKILL.md")))
                .contains("recallContext", "captureDecision", "captureHandoff");

        var second = planner.plan(home, repo, "http://127.0.0.1:8766", true);
        assertThat(second.targets())
                .extracting(ClientOnboardingPlanner.TargetResult::status)
                .containsExactly("unchanged", "unchanged", "unchanged");
    }

    @Test
    void refusesToOverwriteMalformedJsonDotfiles() throws Exception {
        Path home = tempDir.resolve("home");
        Path repo = tempDir.resolve("repo");
        Path claudeSettings = home.resolve(".claude/settings.json");
        Files.createDirectories(claudeSettings.getParent());
        Files.createDirectories(repo);
        Files.writeString(claudeSettings, "{not valid json", StandardCharsets.UTF_8);

        var report = planner.plan(home, repo, "http://127.0.0.1:8766", true);

        assertThat(report.targets().getFirst().status()).isEqualTo("skipped_invalid_json");
        assertThat(Files.readString(claudeSettings, StandardCharsets.UTF_8)).isEqualTo("{not valid json");
    }
}
