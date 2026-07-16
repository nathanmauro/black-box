package dev.nathan.sbaagentic.runner.engine;

import java.util.ArrayList;
import java.util.List;

import dev.nathan.sbaagentic.runner.EngineConfig;

import org.springframework.stereotype.Component;

@Component
public class CodexEngine implements Engine {

    @Override
    public String id() {
        return "codex";
    }

    @Override
    public List<String> command(String prompt, EngineConfig config) {
        List<String> command = new ArrayList<>(List.of(
                "codex",
                "-m",
                config.model(),
                "-c",
                "model_reasoning_effort=\"" + config.effort() + "\"",
                "-a",
                "never"));
        if (config.sandbox() != null && !config.sandbox().isBlank()) {
            command.add("--sandbox");
            command.add(config.sandbox());
            if ("workspace-write".equals(config.sandbox())) {
                // The sandbox denies all network by default, including loopback — but the
                // completion protocol requires workers to POST report.sh annotations to the
                // local Black Box API. Without this, plan/review stages can never report.
                command.add("-c");
                command.add("sandbox_workspace_write.network_access=true");
            }
        }
        // End-of-options marker: goal prompts can open with story frontmatter ("---"),
        // which codex's argument parser would otherwise reject as a malformed flag.
        command.add("--");
        command.add(prompt);
        return List.copyOf(command);
    }
}
