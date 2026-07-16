package dev.nathan.sbaagentic.runner.run;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GoalPromptBuilderTest {

    @Test
    void buildsFrozenStoryThenVerbatimGuardrailsThenTaskSpecificCompletionProtocol() {
        String taskId = "12345678-abcd-4abc-8abc-1234567890ab";
        String story = "---\nstory: v1\n---\n# Frozen story";

        String prompt = new GoalPromptBuilder().build(taskId, story, "mvn test");

        assertThat(prompt.indexOf("# Frozen story"))
                .isLessThan(prompt.indexOf("mvn test"));
        assertThat(prompt.indexOf("mvn test"))
                .isLessThan(prompt.indexOf("## Guardrails"));
        assertThat(prompt.indexOf("## Guardrails"))
                .isLessThan(prompt.indexOf("## Completion protocol"));
        assertThat(prompt).contains(
                GoalPromptBuilder.GUARDRAILS,
                "- Commit identity: Nathan only. No Co-Authored-By naming a model, "
                        + "no generated-by lines, in commits or PR bodies.",
                "- Fail closed: dangers/unknowns → stop and report via "
                        + "`report.sh <taskId> blocked \"<reason>\"`, never improvise around a gate.",
                "scripts/runner/report.sh " + taskId + " done",
                "scripts/runner/report.sh " + taskId + " blocked");
    }

    @Test
    void buildsReadOnlyPlanPromptWithVerbatimGuardrailsAndPlanProtocol() {
        String taskId = "12345678-abcd-4abc-8abc-1234567890ab";
        String story = "---\nstory: v1\n---\n# Frozen story";

        String prompt = new GoalPromptBuilder().buildPlan(taskId, story, "mvn test");

        assertThat(prompt.indexOf("# Frozen story"))
                .isLessThan(prompt.indexOf("## SDLC plan stage"));
        assertThat(prompt.indexOf("## SDLC plan stage"))
                .isLessThan(prompt.indexOf("mvn test"));
        assertThat(prompt.indexOf("mvn test"))
                .isLessThan(prompt.indexOf("## Guardrails"));
        assertThat(prompt.indexOf("## Guardrails"))
                .isLessThan(prompt.indexOf("## Completion protocol"));
        assertThat(prompt).contains(
                GoalPromptBuilder.GUARDRAILS,
                "Explore the repository read-only",
                "Make no commits.",
                "implementation approach",
                "files expected to change",
                "material risks and failure modes",
                "verification plan",
                "scripts/runner/report.sh " + taskId + " plan",
                "scripts/runner/report.sh " + taskId + " done",
                "scripts/runner/report.sh " + taskId + " blocked");
    }

    @Test
    void buildsAdversarialReviewPromptWithApprovedPlanAndVerbatimGuardrails() {
        String taskId = "12345678-abcd-4abc-8abc-1234567890ab";
        String story = "---\nstory: v1\n---\n# Frozen story";
        String approvedPlan = "1. Change Worker.java.\n2. Run the focused tests.";

        String prompt = new GoalPromptBuilder()
                .buildReview(taskId, story, "mvn test", approvedPlan);

        assertThat(prompt.indexOf("# Frozen story"))
                .isLessThan(prompt.indexOf("## Approved plan"));
        assertThat(prompt.indexOf(approvedPlan))
                .isLessThan(prompt.indexOf("## SDLC review stage"));
        assertThat(prompt.indexOf("## SDLC review stage"))
                .isLessThan(prompt.indexOf("mvn test"));
        assertThat(prompt.indexOf("mvn test"))
                .isLessThan(prompt.indexOf("## Guardrails"));
        assertThat(prompt.indexOf("## Guardrails"))
                .isLessThan(prompt.indexOf("## Completion protocol"));
        assertThat(prompt).contains(
                GoalPromptBuilder.GUARDRAILS,
                "adversarial review",
                "diff against the repository's default branch",
                "check every acceptance criterion",
                "advisory and read-only",
                "Make no code changes and no commits",
                "scripts/runner/report.sh " + taskId + " review",
                "scripts/runner/report.sh " + taskId + " done",
                "scripts/runner/report.sh " + taskId + " blocked");
    }
}
