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
}
