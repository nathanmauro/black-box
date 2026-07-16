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

    private static final String PLAN_STAGE = """
            ## SDLC plan stage

            Explore the repository read-only and produce an implementation plan. Make no commits.
            Do not create, edit, delete, move, or format repository files; the plan text reported below is the only output of this stage.

            The plan must cover:
            - the implementation approach;
            - the files expected to change;
            - material risks and failure modes;
            - the verification plan, including the exact verify command supplied below.
            """.stripTrailing();

    private static final String PLAN_COMPLETION_PROTOCOL = """
            ## Completion protocol

            The stage is complete ONLY after you have executed both commands below from the shell.
            Printing the plan as a chat answer does not complete the task — execute:
              <reportScript> <taskId> plan "<implementation plan markdown>"
              <reportScript> <taskId> done "<one-line summary of the plan>"

            If you hit a dangerous, unknown, or blocking situation you cannot resolve within these guardrails, run:
              <reportScript> <taskId> blocked "<one-line reason>"

            Do not modify files, commit, push, open a PR, or merge.
            """.stripTrailing();

    private static final String REVIEW_STAGE = """
            ## SDLC review stage

            Perform an adversarial review of the existing implementation. Read the diff against the repository's default branch, check every acceptance criterion against the implementation and the approved plan, and run the exact verify command supplied below.

            This stage is advisory and read-only. Do not create, edit, delete, move, or format repository files. Make no code changes and no commits, even when you find a defect; record every finding in the review text instead.
            """.stripTrailing();

    private static final String REVIEW_COMPLETION_PROTOCOL = """
            ## Completion protocol

            The stage is complete ONLY after you have executed both commands below from the shell.
            Printing the findings as a chat answer does not complete the task — execute:
              <reportScript> <taskId> review "<review findings markdown>"
              <reportScript> <taskId> done "<one-line summary of the review>"

            If you hit a dangerous, unknown, or blocking situation you cannot resolve within these guardrails, run:
              <reportScript> <taskId> blocked "<one-line reason>"

            Do not modify files, commit, push, open a PR, or merge.
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

    public String buildPlan(String taskId, String storyBody, String resolvedVerify) {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(storyBody, "storyBody");
        Objects.requireNonNull(resolvedVerify, "resolvedVerify");
        String reportScript = RunnerNaming.scriptPath("scripts/runner/report.sh");
        return storyBody
                + "\n\n"
                + PLAN_STAGE
                + "\n\n"
                + "## Verify command to plan for\n\n"
                + "The build stage must run this exact command before committing:\n  "
                + resolvedVerify
                + "\n\n"
                + GUARDRAILS
                + "\n\n"
                + renderProtocol(PLAN_COMPLETION_PROTOCOL, taskId, reportScript);
    }

    public String buildReview(
            String taskId,
            String storyBody,
            String resolvedVerify,
            String approvedPlan) {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(storyBody, "storyBody");
        Objects.requireNonNull(resolvedVerify, "resolvedVerify");
        Objects.requireNonNull(approvedPlan, "approvedPlan");
        String reportScript = RunnerNaming.scriptPath("scripts/runner/report.sh");
        return storyBody
                + "\n\n"
                + "## Approved plan\n\n"
                + approvedPlan
                + "\n\n"
                + REVIEW_STAGE
                + "\n\n"
                + "## Verify\n\n"
                + "Run this exact command as part of the review:\n  "
                + resolvedVerify
                + "\n\n"
                + GUARDRAILS
                + "\n\n"
                + renderProtocol(REVIEW_COMPLETION_PROTOCOL, taskId, reportScript);
    }

    private static String renderProtocol(String protocol, String taskId, String reportScript) {
        return protocol.replace("<taskId>", taskId).replace("<reportScript>", reportScript);
    }
}
