package dev.nathan.sbaagentic.recording.internal.application;

import static org.assertj.core.api.Assertions.assertThat;

import dev.nathan.sbaagentic.recording.AgentEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class RecordedConversationIndexTest {
    private static final Instant START = Instant.parse("2026-11-10T12:00:00Z");

    @ParameterizedTest(name = "{0}")
    @MethodSource("duplicateCases")
    void preservesRoleTextTurnAndTimeRules(
            String label, AgentEvent recorded, AgentEvent transcript, boolean duplicate) {
        assertThat(new RecordedConversationIndex(List.of(recorded)).contains(transcript))
                .isEqualTo(duplicate);
    }

    private static Stream<Arguments> duplicateCases() {
        AgentEvent recorded = event("assistant", "Stop", "A\tb\nC", "recorded-turn", START);
        AgentEvent candidate = event("assistant", "TranscriptMessage", "a b c", null, START);

        return Stream.of(
                Arguments.of("normalized text", recorded, candidate, true),
                Arguments.of(
                        "positive inclusive boundary",
                        recorded,
                        event("assistant", "TranscriptMessage", "a b c", null, START.plusSeconds(120)),
                        true),
                Arguments.of(
                        "negative inclusive boundary",
                        recorded,
                        event("assistant", "TranscriptMessage", "a b c", null, START.minusSeconds(120)),
                        true),
                Arguments.of(
                        "positive nanosecond outside boundary",
                        recorded,
                        event(
                                "assistant",
                                "TranscriptMessage",
                                "a b c",
                                null,
                                START.plusSeconds(120).plusNanos(1)),
                        false),
                Arguments.of(
                        "negative nanosecond outside boundary",
                        recorded,
                        event(
                                "assistant",
                                "TranscriptMessage",
                                "a b c",
                                null,
                                START.minusSeconds(120).minusNanos(1)),
                        false),
                Arguments.of(
                        "same turn outside window",
                        recorded,
                        event("assistant", "TranscriptMessage", "a b c", "recorded-turn", START.plusSeconds(10000)),
                        true),
                Arguments.of(
                        "same turn with different text",
                        recorded,
                        event("assistant", "TranscriptMessage", "different", "recorded-turn", START),
                        false),
                Arguments.of(
                        "same turn with different role",
                        recorded,
                        event("user", "TranscriptMessage", "a b c", "recorded-turn", START),
                        false),
                Arguments.of(
                        "transcript role remains case sensitive",
                        recorded,
                        event("Assistant", "TranscriptMessage", "a b c", "recorded-turn", START),
                        false),
                Arguments.of(
                        "recorded explicit role is normalized",
                        event("ASSISTANT", "tool", "a b c", null, START),
                        candidate,
                        true),
                Arguments.of(
                        "legacy final role fallback",
                        event("agent", "Final-Response", "a b c", null, START),
                        candidate,
                        true),
                Arguments.of(
                        "legacy prompt role fallback",
                        event(null, "USER_PROMPT_SUBMIT", "a b c", null, START),
                        event("user", "TranscriptMessage", "a b c", null, START),
                        true),
                Arguments.of(
                        "blank final message has no fallback role",
                        event("agent", "Stop", "  ", null, START),
                        event("assistant", "TranscriptMessage", "", null, START),
                        false),
                Arguments.of(
                        "explicit role keeps null text normalization",
                        event("assistant", "Stop", null, null, START),
                        event("assistant", "TranscriptMessage", "", null, START),
                        true),
                Arguments.of(
                        "null turns do not match outside window",
                        event("assistant", "Stop", "a b c", null, START),
                        event("assistant", "TranscriptMessage", "a b c", null, START.plusSeconds(121)),
                        false),
                Arguments.of(
                        "empty non-null turn still identifies a match",
                        event("assistant", "Stop", "a b c", "", START),
                        event("assistant", "TranscriptMessage", "a b c", "", START.plusSeconds(121)),
                        true),
                Arguments.of(
                        "instant lower bound",
                        event("assistant", "Stop", "a b c", null, Instant.MIN),
                        event("assistant", "TranscriptMessage", "a b c", null, Instant.MIN.plusSeconds(120)),
                        true),
                Arguments.of(
                        "instant upper bound",
                        event("assistant", "Stop", "a b c", null, Instant.MAX),
                        event("assistant", "TranscriptMessage", "a b c", null, Instant.MAX.minusSeconds(120)),
                        true));
    }

    @Test
    void findsTheNearestOccurrenceWhenEqualTextAppearsThroughoutTheSession() {
        var index = new RecordedConversationIndex(List.of(
                event("assistant", "Stop", "Repeated", "first", START.plusSeconds(1000)),
                event("assistant", "Stop", "Repeated", "second", START.minusSeconds(500)),
                event("assistant", "Stop", "Repeated", "third", START.plusSeconds(120)),
                event("assistant", "Stop", "Repeated", "fourth", START.minusSeconds(121))));

        assertThat(index.contains(event("assistant", "TranscriptMessage", "repeated", null, START)))
                .isTrue();
        assertThat(index.contains(event(
                        "assistant",
                        "TranscriptMessage",
                        "repeated",
                        null,
                        START.plusSeconds(240).plusNanos(1))))
                .isFalse();
        assertThat(index.contains(
                        event("assistant", "TranscriptMessage", "repeated", "second", START.plusSeconds(10000))))
                .isTrue();
    }

    @Test
    void agreesWithThePreviousPairwiseRuleForMixedLegacyEvents() {
        Random random = new Random(613);
        List<AgentEvent> recorded = new ArrayList<>();
        for (int index = 0; index < 80; index++) {
            recorded.add(randomEvent(random));
        }
        var lookup = new RecordedConversationIndex(recorded);
        for (int index = 0; index < 500; index++) {
            AgentEvent candidate = randomEvent(random);
            boolean previous = recorded.stream().anyMatch(event -> previousDuplicateRule(event, candidate));
            assertThat(lookup.contains(candidate)).as("candidate %s", index).isEqualTo(previous);
        }
    }

    private static AgentEvent randomEvent(Random random) {
        String[] roles = {"assistant", "user", "agent", null, "Assistant"};
        String[] types = {"Stop", "UserPromptSubmit", "tool", "Agent-Response", "FinalResponse"};
        String[] texts = {"a b", " A\tB ", "\n", null, "third", "café", "a\u00a0b"};
        String[] turns = {null, "", "one", "two"};

        return event(
                roles[random.nextInt(roles.length)],
                types[random.nextInt(types.length)],
                texts[random.nextInt(texts.length)],
                turns[random.nextInt(turns.length)],
                START.plusSeconds(random.nextInt(1200) - 600).plusNanos(random.nextInt(2)));
    }

    // Compatibility oracle copied from the former pairwise service comparison.
    private static boolean previousDuplicateRule(AgentEvent recorded, AgentEvent transcript) {
        String role = lower(recorded.role());
        if (!role.equals("user") && !role.equals("assistant")) {
            String type = lower(recorded.eventType()).replaceAll("[^a-z0-9]", "");
            if (type.equals("userpromptsubmit") || type.equals("beforesubmitprompt")) {
                role = "user";
            } else if (recorded.text() != null
                    && !recorded.text().isBlank()
                    && List.of("stop", "assistantmessage", "agentmessage", "agentresponse", "finalresponse")
                            .contains(type)) {
                role = "assistant";
            } else {

                return false;
            }
        }
        if (!role.equals(transcript.role())
                || !lower(recorded.text())
                        .replaceAll("\\s+", " ")
                        .trim()
                        .equals(lower(transcript.text()).replaceAll("\\s+", " ").trim())) {

            return false;
        }
        if (recorded.turnId() != null && recorded.turnId().equals(transcript.turnId())) {

            return true;
        }

        return Duration.between(recorded.observedAt(), transcript.observedAt())
                        .abs()
                        .compareTo(Duration.ofMinutes(2))
                <= 0;
    }

    private static String lower(String text) {

        return (text == null ? "" : text).toLowerCase(Locale.ROOT);
    }

    private static AgentEvent event(String role, String type, String text, String turn, Instant at) {

        return new AgentEvent(
                "event", "fixture", "claude", "fixture", turn, type, role, text, null, null, null, Map.of(), at);
    }
}
