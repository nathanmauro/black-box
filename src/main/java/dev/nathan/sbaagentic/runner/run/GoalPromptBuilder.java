package dev.nathan.sbaagentic.runner.run;

import java.util.Objects;

import dev.nathan.sbaagentic.runner.RunnerNaming;

import org.springframework.stereotype.Component;

@Component
public class GoalPromptBuilder {

    static final String GUARDRAILS = """
            ## Guardrails

            - Commit identity: Nathan only. No Co-Authored-By naming a model, no generated-by lines, in commits or PR bodies.
            - Branch-only work: never touch the default branch; never `git add -A`; stage explicitly; exclude `.claude/`, `.idea/`, pre-existing dirty files.
            - Verify before commit; only green work is committed.
            - Fail closed: dangers/unknowns → stop and report via `report.sh <taskId> blocked "<reason>"`, never improvise around a gate.
            - The worker never pushes or opens PRs — the runner's `ship.sh` owns that.
            """.stripTrailing();

    private static final String COMPLETION_PROTOCOL = """
            ## Completion protocol

            When verify is green and your work is committed, run:
              <reportScript> <taskId> done "<one-line summary of what you did>"

            If you hit a dangerous, unknown, or blocking situation you cannot resolve within these guardrails, run:
              <reportScript> <taskId> blocked "<one-line reason>"

            Do not push, open a PR, or merge. Do not modify files outside this worktree.
            """.stripTrailing();

    public String build(String taskId, String storyBody, String resolvedVerify) {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(storyBody, "storyBody");
        Objects.requireNonNull(resolvedVerify, "resolvedVerify");
        // Absolute path: the worker's cwd is the target repo's worktree, not the sba-agentic
        // checkout this script lives in (see RunnerNaming.scriptPath).
        String reportScript = RunnerNaming.scriptPath("scripts/runner/report.sh");
        return storyBody
                + "\n\n"
                + "## Verify\n\n"
                + "Run this exact command before every commit; only proceed once it is green:\n  "
                + resolvedVerify
                + "\n\n"
                + GUARDRAILS
                + "\n\n"
                + COMPLETION_PROTOCOL.replace("<taskId>", taskId).replace("<reportScript>", reportScript);
    }
}
