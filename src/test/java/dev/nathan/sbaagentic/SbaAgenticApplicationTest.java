package dev.nathan.sbaagentic;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SbaAgenticApplicationTest {
    @Test
    void retiredRunnerCommandIsRejectedBeforeSpringStarts() {
        assertThatThrownBy(() -> SbaAgenticApplication.main(new String[] {"runner"}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unknown command: runner");
    }
}
