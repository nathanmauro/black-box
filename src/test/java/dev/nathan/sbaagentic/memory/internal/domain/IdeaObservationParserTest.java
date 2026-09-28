package dev.nathan.sbaagentic.memory.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;

import dev.nathan.sbaagentic.memory.internal.domain.IdeaObservationParser.ParsedIdea;
import org.junit.jupiter.api.Test;

class IdeaObservationParserTest {

    static final String LANES_BOARD = """
            [Idea] A live lanes board: one lane per project, with items routed to their lane and cross-listed in others.
            - origin: nathan-aside, 2026-09-28 ~15:20 ET, in the ~/bin session 6a060210. Verbatim: "I'm starting to visualize a live board with lanes for all my projects ...".
            - What: a live board with one swimlane per project. Every incoming event ... is routed to its home lane.
            - Prior art:
              - Constellate's idea sky ...
            - legs: 8
            - connects: Constellate/Orbit (NAT-196 In Review), Black Box project identity and routing, the Idea/Evidence kinds (which need primary and secondary lane fields)
            - status: untouched. Nathan: "I'm just dumping it out.\"""";

    static final String EVIDENCE_KIND = """
            [Idea] Black Box Evidence capture kind.
            - origin: nathan-aside, ... Verbatim: "maybe evidence should be captured too".
            - What: make Evidence a first-class capture kind next to the new Idea kind. ...
            - legs: 7
            - connects: Black Box Idea kind, human-turn-first, verification-before-completion habits
            - status: untouched. Planned for a separate Black Box "additions" session.""";

    @Test
    void parsesTheLanesBoardObservation() {
        ParsedIdea idea = IdeaObservationParser.parse(LANES_BOARD);

        assertThat(idea.title())
                .isEqualTo("A live lanes board: one lane per project, with items routed to their lane and "
                        + "cross-listed in others.");
        assertThat(idea.oneLiner()).isEqualTo("a live board with one swimlane per project.");
        assertThat(idea.origin()).isEqualTo("human-aside");
        assertThat(idea.quote()).isEqualTo("I'm starting to visualize a live board with lanes for all my projects ...");
        assertThat(idea.legs()).isEqualTo(8);
        assertThat(idea.status()).isEqualTo("untouched");
        assertThat(idea.connects())
                .containsExactly(
                        "Constellate/Orbit (NAT-196 In Review)",
                        "Black Box project identity and routing",
                        "the Idea/Evidence kinds (which need primary and secondary lane fields)");
        assertThat(idea.warnings()).isEmpty();
    }

    @Test
    void parsesTheEvidenceKindObservation() {
        ParsedIdea idea = IdeaObservationParser.parse(EVIDENCE_KIND);

        assertThat(idea.title()).isEqualTo("Black Box Evidence capture kind.");
        assertThat(idea.oneLiner()).isEqualTo("make Evidence a first-class capture kind next to the new Idea kind.");
        assertThat(idea.origin()).isEqualTo("human-aside");
        assertThat(idea.quote()).isEqualTo("maybe evidence should be captured too");
        assertThat(idea.legs()).isEqualTo(7);
        assertThat(idea.status()).isEqualTo("untouched");
        assertThat(idea.connects())
                .containsExactly("Black Box Idea kind", "human-turn-first", "verification-before-completion habits");
        assertThat(idea.warnings()).isEmpty();
    }

    @Test
    void missingAndUnknownValuesBecomeWarningsNotFailures() {
        ParsedIdea idea = IdeaObservationParser.parse("""
                [Idea] Bare idea
                - origin: hallway-chat, somewhere
                - legs: twelve
                - status: someday. maybe""");

        assertThat(idea.title()).isEqualTo("Bare idea");
        assertThat(idea.oneLiner()).isEqualTo("Bare idea");
        assertThat(idea.origin()).isEqualTo("agent-proposed");
        assertThat(idea.quote()).isNull();
        assertThat(idea.legs()).isNull();
        assertThat(idea.status()).isEqualTo("untouched");
        assertThat(idea.connects()).isEmpty();
        assertThat(idea.warnings())
                .containsExactly(
                        "unknown origin 'hallway-chat': defaulted to agent-proposed",
                        "missing quote: no Verbatim: \"…\" found",
                        "missing What: one-liner falls back to the title",
                        "unreadable legs 'twelve': dropped",
                        "unknown status 'someday': defaulted to untouched",
                        "missing connects");
    }

    @Test
    void outOfRangeLegsAndMissingBulletsAreWarned() {
        ParsedIdea idea = IdeaObservationParser.parse("[Idea] Only a title\n- legs: 42");

        assertThat(idea.legs()).isNull();
        assertThat(idea.warnings())
                .contains(
                        "missing origin: defaulted to agent-proposed",
                        "legs 42 is outside 0..10: dropped",
                        "missing status: defaulted to untouched");
    }

    @Test
    void overflowingLegsAreWarnedInsteadOfThrowing() {
        ParsedIdea idea = IdeaObservationParser.parse("[Idea] Big number\n- legs: 12345678901\n- status: tracked");

        assertThat(idea.legs()).isNull();
        assertThat(idea.status()).isEqualTo("tracked");
        assertThat(idea.warnings()).contains("legs 12345678901 is outside 0..10: dropped");
        assertThat(IdeaObservationParser.parse("[Idea] Negative\n- legs: -99999999999")
                        .warnings())
                .contains("legs -99999999999 is outside 0..10: dropped");
    }

    @Test
    void emptyTitleIsWarned() {
        ParsedIdea idea = IdeaObservationParser.parse("[Idea]\n- What: something.");

        assertThat(idea.title()).isNull();
        assertThat(idea.oneLiner()).isEqualTo("something.");
        assertThat(idea.warnings()).contains("missing title: the first line after [Idea] is empty");
    }

    @Test
    void connectsSplitsOnlyOnCommasOutsideParentheses() {
        assertThat(IdeaObservationParser.splitOutsideParentheses("a, b (x, y), c (p (q, r), s), , d"))
                .containsExactly("a", "b (x, y)", "c (p (q, r), s)", "d");
    }

    @Test
    void recognisesOnlyPrefixedObservations() {
        assertThat(IdeaObservationParser.isIdeaObservation("[Idea] yes")).isTrue();
        assertThat(IdeaObservationParser.isIdeaObservation("An [Idea] later")).isFalse();
        assertThat(IdeaObservationParser.isIdeaObservation(null)).isFalse();
    }
}
