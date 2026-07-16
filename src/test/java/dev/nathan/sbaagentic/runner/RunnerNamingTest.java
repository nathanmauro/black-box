package dev.nathan.sbaagentic.runner;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RunnerNamingTest {

    @Test
    void derivesStableNamesFromTheFirstEightTaskIdCharacters() {
        String taskId = "12345678-abcd-4abc-8abc-1234567890ab";

        assertThat(RunnerNaming.taskShort(taskId)).isEqualTo("12345678");
        assertThat(RunnerNaming.tmuxSessionName(taskId)).isEqualTo("bb-run-12345678");
        assertThat(RunnerNaming.worktreeDirName(taskId)).isEqualTo(".worktrees/bb-12345678");
    }
}
