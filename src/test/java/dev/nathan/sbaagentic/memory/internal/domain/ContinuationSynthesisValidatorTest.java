package dev.nathan.sbaagentic.memory.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

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

    @ParameterizedTest
    @ValueSource(
            strings = {
                "Restated [E1].",
                "Restated [E1, E2] and [E1,E2].",
                "Restated [E1]; E2 says the same and e1 agrees.",
                "Restated [E1]. [Edit] and E2E tests are prose; so is IE11."
            })
    void acceptsEveryMentionOfAKnownHandle(String content) {
        assertThat(validate(content, "stop").valid()).isTrue();
    }

    /** Only E1 is known, so each reply also carries a valid [E1] citation and fails on the other mention alone. */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "Restated [E1]; see [E9; E1].",
                "Restated [E1] (E9).",
                "Restated [E1]; see [E9 and E1].",
                "Restated [E1] [e9].",
                "Restated [E1] as E9 recorded.",
                "Restated [E1]; see [E2].",
                "Restated [E9; E1].",
                "Restated (E9).",
                "Restated [E9 and E1].",
                "Restated [e9]."
            })
    void rejectsAnUnknownHandleInAnyForm(String content) {
        var verdict = ContinuationSynthesisValidator.validate(content, "stop", HANDLES.subList(0, 1), 4_000);

        assertThat(verdict.valid()).isFalse();
        assertThat(verdict.reason()).isEqualTo("invalid_reference");
        assertThat(verdict.citations()).isEmpty();
    }

    @Test
    void acceptsAKnownEventIdButNoOtherId() {
        assertThat(validate("Recorded in event:" + KNOWN + " [E1].", "stop").valid())
                .isTrue();
        assertThat(validate("Recorded in " + KNOWN.toUpperCase() + " [E1].", "stop")
                        .valid())
                .isTrue();
    }

    @Test
    void aKnownEventIdIsNotReadAsAHandle() {
        // "e123" is a hex group of this event ID; the handle scan must not see it as an unknown handle.
        String eventId = "3f2a0000-e123-4000-8000-000000000001";
        var handles =
                List.of(new ContinuationHandle("E1", eventId, "s1", "handoff", Instant.parse("2026-10-14T12:00:00Z")));

        assertThat(ContinuationSynthesisValidator.validate(
                                "Recorded in event:" + eventId + " [E1].", "stop", handles, 4_000)
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
