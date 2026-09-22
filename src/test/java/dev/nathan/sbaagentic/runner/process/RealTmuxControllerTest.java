package dev.nathan.sbaagentic.runner.process;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.util.List;
import org.junit.jupiter.api.Test;

class RealTmuxControllerTest {

    @Test
    void recognizesPresentAndExplicitlyAbsentSessions() {
        assertThat(controller(new ProcessRunner.ProcessResult(0, "", "", false)).hasSession("worker"))
                .isTrue();
        for (String message : List.of(
                "can't find session: worker",
                "no server running on /tmp/tmux-fixture/default",
                "error connecting to /tmp/tmux-fixture/default (No such file or directory)")) {
            assertThat(controller(new ProcessRunner.ProcessResult(1, "", message, false))
                            .hasSession("worker"))
                    .isFalse();
        }
    }

    @Test
    void failedOrTimedOutProbesCannotClaimWorkerStopped() {
        for (ProcessRunner.ProcessResult result : List.of(
                new ProcessRunner.ProcessResult(0, "", "", true),
                new ProcessRunner.ProcessResult(-1, "", "Interrupted", false),
                new ProcessRunner.ProcessResult(
                        1, "", "error connecting to /tmp/tmux-fixture/default (Permission denied)", false),
                new ProcessRunner.ProcessResult(1, "", "", false),
                new ProcessRunner.ProcessResult(127, "", "tmux unavailable", false))) {
            assertThatIllegalStateException()
                    .isThrownBy(() -> controller(result).hasSession("worker"))
                    .withMessageContaining("Unable to inspect session worker");
        }
    }

    private RealTmuxController controller(ProcessRunner.ProcessResult result) {

        return new RealTmuxController((command, directory, timeout) -> {
            assertThat(command).containsExactly("tmux", "has-session", "-t", "worker");

            return result;
        });
    }
}
