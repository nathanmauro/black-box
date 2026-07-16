package dev.nathan.sbaagentic.runner;

import dev.nathan.sbaagentic.runner.engine.RateLimitDetector;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitDetectorTest {

    @Test
    void recognizesEveryRateLimitPatternCaseInsensitively() {
        assertThat(RateLimitDetector.matches("request failed with 429")).isTrue();
        assertThat(RateLimitDetector.matches("RATE LIMIT reached")).isTrue();
        assertThat(RateLimitDetector.matches("Usage Limit exceeded")).isTrue();
        assertThat(RateLimitDetector.matches("Provider QUOTA exhausted")).isTrue();
    }

    @Test
    void ignoresOrdinaryAndMissingPaneText() {
        assertThat(RateLimitDetector.matches("worker completed successfully")).isFalse();
        assertThat(RateLimitDetector.matches("")).isFalse();
        assertThat(RateLimitDetector.matches(null)).isFalse();
    }
}
