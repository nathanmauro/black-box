package dev.nathan.sbaagentic.memory.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ContinuationSynthesisValidatorTest {

    static final String KNOWN = "3f2a0000-0000-4000-8000-000000000001";
    static final List<ContinuationHandle> HANDLES = List.of(
            new ContinuationHandle("E1", KNOWN, "s1", "handoff", Instant.parse("2026-10-14T12:00:00Z")),
            new ContinuationHandle(
                    "E2",
                    "9d9d0000-0000-4000-8000-000000000003",
                    "s1",
                    "decision",
                    Instant.parse("2026-10-14T13:00:00Z")));

    static ContinuationSynthesisValidator.Verdict validate(String content, String finishReason) {

        return ContinuationSynthesisValidator.validate(content, finishReason, HANDLES, 4_000);
    }

    @Test
    void acceptsAReplyCitingKnownHandlesAndReportsThemInFirstMentionOrder() {
        var verdict = validate(
                "  Reproduce the recovery path [E2]; storage was replaced [E1, E2]. [Edit] is prose.  ", "stop");

        assertThat(verdict.valid()).isTrue();
        assertThat(verdict.reason()).isNull();
        assertThat(verdict.text()).startsWith("Reproduce").endsWith("prose.");
        assertThat(verdict.citations()).extracting(ContinuationHandle::handle).containsExactly("E2", "E1");
    }

    @Test
    void acceptsAKnownEventIdButNoOtherId() {
        assertThat(validate("Recorded in event:" + KNOWN + " [E1].", "stop").valid())
                .isTrue();
        assertThat(validate("Recorded in " + KNOWN.toUpperCase() + " [E1].", "stop")
                        .valid())
                .isTrue();
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "'   '|stop|empty",
                "Restated [E1].|length|truncated",
                "Restated [E1].|''|truncated",
                "Restated [E9].|stop|invalid_reference",
                "Restated [E01].|stop|invalid_reference",
                "Restated [E1, E7].|stop|invalid_reference",
                "Restated without any citation.|stop|invalid_reference",
                "Restated [E1]; see 11111111-2222-4333-8444-555555555555.|stop|invalid_reference",
                "Restated [E1]; see event:abcdef12.|stop|invalid_reference"
            })
    void rejectsTheWholeReplyWithAFixedReason(String content, String finishReason, String reason) {
        var verdict = validate(content, finishReason.isEmpty() ? null : finishReason);

        assertThat(verdict.valid()).isFalse();
        assertThat(verdict.reason()).isEqualTo(reason);
        assertThat(verdict.citations()).isEmpty();
        assertThat(verdict.text()).isNull();
    }

    @Test
    void rejectsAReplyOverTheOutputBound() {
        var verdict = ContinuationSynthesisValidator.validate("[E1] " + "x".repeat(50), "stop", HANDLES, 20);

        assertThat(verdict.reason()).isEqualTo("truncated");
    }
}
