package dev.nathan.sbaagentic.runner.engine;

import java.util.List;

import dev.nathan.sbaagentic.runner.EngineConfig;
import dev.nathan.sbaagentic.runner.RunnerNaming;

import org.springframework.stereotype.Component;

@Component
public class FakeEngine implements Engine {

    @Override
    public String id() {
        return "fake";
    }

    @Override
    public List<String> command(String prompt, EngineConfig config) {
        // The run executor supplies SBA_TASK_ID and SBA_WORKTREE instead of passing a large prompt.
        // Absolute path: the tmux pane's cwd is the target repo's worktree, not the sba-agentic
        // checkout these scripts live in (see RunnerNaming.scriptPath).
        return List.of(RunnerNaming.scriptPath("scripts/runner/fake-worker.sh"));
    }
}
