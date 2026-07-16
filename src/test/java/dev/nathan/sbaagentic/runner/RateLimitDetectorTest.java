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

    @Test
    void ignoresInformationalLimitTextFromEngineUi() {
        assertThat(RateLimitDetector.matches(
                "You have 3 usage limit resets available. Run /usage to use one.")).isFalse();
        assertThat(RateLimitDetector.matches("Fast on · weekly limit 90% left")).isFalse();
        assertThat(RateLimitDetector.matches("5h limit 88% left")).isFalse();
        assertThat(RateLimitDetector.matches("respect the repo quota conventions")).isFalse();
    }

    @Test
    void recognizesGenuineExhaustionWording() {
        assertThat(RateLimitDetector.matches("You've hit your usage limit. Try again later.")).isTrue();
        assertThat(RateLimitDetector.matches("stream error: rate-limited by upstream")).isTrue();
        assertThat(RateLimitDetector.matches("quota exceeded for gpt-5.6-sol")).isTrue();
    }

    @Test
    void ignores429InsideLineNumbersAndDurations() {
        assertThat(RateLimitDetector.matches("line 4290 failed\nrequest took 429ms"))
                .isFalse();
    }

    @Test
    void recognizes429InHttpErrorContexts() {
        assertThat(RateLimitDetector.matches("HTTP 429")).isTrue();
        assertThat(RateLimitDetector.matches("status 429: Too Many Requests")).isTrue();
    }
}
