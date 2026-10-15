package dev.nathan.sbaagentic.memory.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import dev.nathan.sbaagentic.memory.internal.domain.ContinuationCapture.Kind;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ContinuationRankerTest {

    static final String ROOT = "/repos/alpha";
    static final Instant NOW = Instant.parse("2026-10-15T12:00:00Z");

    static ContinuationCapture handoff(String id, String session, Duration age, String nextAction, String... loops) {

        return new ContinuationCapture(
                id, session, Kind.HANDOFF, NOW.minus(age), "Summary " + id, null, List.of(loops), nextAction);
    }

    static ContinuationCapture decision(String id, Duration age, String... loops) {

        return new ContinuationCapture(
                id, "decider", Kind.DECISION, NOW.minus(age), "Decision " + id, "Because " + id, List.of(loops), null);
    }

    static Duration days(long days) {

        return Duration.ofDays(days);
    }

    static Duration hours(long hours) {

        return Duration.ofHours(hours);
    }

    @Test
    void headIsTheLatestHandoffInTheNewestBurstAndTheFrontierKeepsSevenDaysAroundIt() {
        var older = handoff("h-old", "s-old", days(30), "Old step");
        var nearby = handoff("h-near", "s3", days(8), "Third step");
        var second = handoff("h2", "s2", days(3), "Write the docs");
        var head = handoff("h1", "s1", days(2), "Ship the parser");
        var newest = decision("d1", days(1));

        var frontier = ContinuationRanker.rank(ROOT, List.of(older, nearby, second, head), List.of(newest), NOW);

        assertThat(frontier.headAt()).isEqualTo(NOW.minus(days(2)));
        assertThat(frontier.newestEvidenceAt()).isEqualTo(NOW.minus(days(1)));
        assertThat(frontier.stale()).isFalse();
        assertThat(frontier.frontierHandoffs())
                .extracting(ContinuationCapture::eventId)
                .containsExactly("h1", "h2", "h-near");
        // At most two recorded next steps, newest first; the 30-day-old handoff is outside the frontier.
        assertThat(frontier.candidates())
                .extracting(ContinuationCandidate::text)
                .containsExactly("Ship the parser", "Write the docs");
        assertThat(frontier.candidates())
                .extracting(candidate -> candidate.basis().wire())
                .containsOnly("handoff.nextAction");
        assertThat(frontier.candidates())
                .extracting(ContinuationCandidate::rank)
                .containsExactly(1, 2);
    }

    @Test
    void theNewestHandoffPerSessionWins() {
        var earlier = handoff("h-a", "s1", hours(5), "Earlier step");
        var later = handoff("h-b", "s1", hours(1), "Later step");

        var frontier = ContinuationRanker.rank(ROOT, List.of(earlier, later), List.of(), NOW);

        assertThat(frontier.frontierHandoffs())
                .extracting(ContinuationCapture::eventId)
                .containsExactly("h-b");
        assertThat(frontier.candidates())
                .extracting(ContinuationCandidate::text)
                .containsExactly("Later step");
    }

    @Test
    void headFallsBackToTheNewestCaptureWhenTheNewestBurstHasNoHandoff() {
        var oldHandoff = handoff("h1", "s1", days(10), "Stale handoff step");
        var newestDecision = decision("d1", days(1), "Measure the restore drill");

        var frontier = ContinuationRanker.rank(ROOT, List.of(oldHandoff), List.of(newestDecision), NOW);

        assertThat(frontier.headAt()).isEqualTo(NOW.minus(days(1)));
        assertThat(frontier.frontierHandoffs()).isEmpty();
        assertThat(frontier.candidates()).singleElement().satisfies(candidate -> {
            assertThat(candidate.text()).isEqualTo("Measure the restore drill");
            assertThat(candidate.basis()).isEqualTo(ContinuationBasis.DECISION_OPEN_LOOP);
        });
    }

    @Test
    void staleWhenTheHeadIsOlderThanFourteenDays() {
        var frontier = ContinuationRanker.rank(
                ROOT, List.of(handoff("h1", "s1", days(20), "Resume the migration")), List.of(), NOW);

        assertThat(frontier.stale()).isTrue();
        assertThat(frontier.candidates()).singleElement().satisfies(candidate -> {
            assertThat(candidate.ageDays()).isEqualTo(20);
            assertThat(candidate.stale()).isTrue();
        });
        assertThat(ContinuationRanker.rank(
                                ROOT, List.of(handoff("h1", "s1", days(14), "Resume the migration")), List.of(), NOW)
                        .stale())
                .isFalse();
    }

    @Test
    void aFutureDatedCaptureCountsAsNowAndIsFlagged() {
        var future = new ContinuationCapture(
                "h-future", "s1", Kind.HANDOFF, NOW.plus(days(3)), "Forward dated", null, List.of(), "Check the clock");

        var frontier = ContinuationRanker.rank(ROOT, List.of(future), List.of(), NOW);

        assertThat(frontier.headAt()).isEqualTo(NOW);
        assertThat(frontier.futureDated()).isTrue();
        assertThat(frontier.candidates()).singleElement().satisfies(candidate -> {
            assertThat(candidate.recordedAt()).isEqualTo(NOW);
            assertThat(candidate.ageDays()).isZero();
            assertThat(candidate.futureDated()).isTrue();
        });
    }

    @Test
    void tiersRankNextActionsThenHandoffLoopsThenDecisionLoopsRegardlessOfRecency() {
        var handoff = handoff("h1", "s1", hours(2), "Run the release checklist", "Confirm the signing key rotation");
        var newerDecision = decision("d1", hours(1), "Benchmark the cold start");

        var frontier = ContinuationRanker.rank(ROOT, List.of(handoff), List.of(newerDecision), NOW);

        assertThat(frontier.candidates())
                .extracting(candidate -> candidate.basis().wire() + ":" + candidate.text())
                .containsExactly(
                        "handoff.nextAction:Run the release checklist",
                        "handoff.openLoop:Confirm the signing key rotation",
                        "decision.openLoop:Benchmark the cold start");
    }

    @Test
    void decisionsFarFromTheHeadContributeNoOpenLoops() {
        var head = handoff("h1", "s1", hours(1), "Ship the parser");
        var oldDecision = decision("d-old", days(10), "An old open question");

        var frontier = ContinuationRanker.rank(ROOT, List.of(head), List.of(oldDecision), NOW);

        assertThat(frontier.candidates())
                .extracting(ContinuationCandidate::text)
                .containsExactly("Ship the parser");
        assertThat(frontier.decisions())
                .extracting(ContinuationCapture::eventId)
                .containsExactly("d-old");
    }

    @Test
    void noActionTextAndBlankLoopsAreDropped() {
        var handoff = handoff("h1", "s1", hours(1), "None", "n/a", "Done.", "   ", "Re-run the flaky suite");

        var frontier = ContinuationRanker.rank(ROOT, List.of(handoff), List.of(), NOW);

        assertThat(frontier.candidates())
                .extracting(ContinuationCandidate::text)
                .containsExactly("Re-run the flaky suite");
    }

    @Test
    void duplicatesMergeUnderTheStrongestSourceWithTheUnionOfEvidence() {
        var first = handoff("h1", "s1", hours(3), null, "Verify recovery before shared access");
        var second = handoff("h2", "s2", hours(2), null, "verify   RECOVERY before shared access");
        var decision = decision("d1", hours(1), "Verify the recovery path before shared access");

        var frontier = ContinuationRanker.rank(ROOT, List.of(first, second), List.of(decision), NOW);

        assertThat(frontier.candidates()).singleElement().satisfies(candidate -> {
            // The label, text and id all describe the strongest source; the newer decision loop stays
            // in the evidence instead of renaming a handoff loop.
            assertThat(candidate.text()).isEqualTo("verify RECOVERY before shared access");
            assertThat(candidate.basis()).isEqualTo(ContinuationBasis.HANDOFF_OPEN_LOOP);
            assertThat(candidate.primary().capture().eventId()).isEqualTo("h2");
            assertThat(candidate.sources())
                    .extracting(source -> source.capture().eventId())
                    .containsExactly("d1", "h2", "h1");
            assertThat(candidate.recordedAt()).isEqualTo(NOW.minus(hours(1)));
            assertThat(candidate.id())
                    .isEqualTo(ContinuationText.candidateId(ROOT, "verify RECOVERY before shared access"));
        });
    }

    @Test
    void distinctActionsThatShareAnIdentifierStaySeparate() {
        var marker = "93cb26eb-b67d-48e7-bb68-16733cdad90d";
        var root = handoff(
                "h1", "s1", hours(2), "Reproduce the recovery path " + marker, "Verify recovery before shared access");
        var alias = handoff("h2", "s2", hours(1), "Merge the worktree slice " + marker);
        var decision = decision("d1", hours(0), "Measure the restore drill " + marker);

        var frontier = ContinuationRanker.rank(ROOT, List.of(root, alias), List.of(decision), NOW);

        assertThat(frontier.candidates())
                .extracting(ContinuationCandidate::basis, ContinuationCandidate::text)
                .containsExactly(
                        tuple(ContinuationBasis.HANDOFF_NEXT_ACTION, "Merge the worktree slice " + marker),
                        tuple(ContinuationBasis.HANDOFF_NEXT_ACTION, "Reproduce the recovery path " + marker),
                        tuple(ContinuationBasis.HANDOFF_OPEN_LOOP, "Verify recovery before shared access"),
                        tuple(ContinuationBasis.DECISION_OPEN_LOOP, "Measure the restore drill " + marker));
    }

    @Test
    void nearDuplicatesAreComparedWithEachGroupsPrimaryTextSoTheyCannotChain() {
        String a = "Reproduce the recovery path today";
        String b = "Reproduce the recovery path tomorrow";
        String c = "Document the recovery path tomorrow";
        assertThat(ContinuationText.jaccard(a, b)).isGreaterThanOrEqualTo(ContinuationRanker.DUPLICATE_SIMILARITY);
        assertThat(ContinuationText.jaccard(b, c)).isGreaterThanOrEqualTo(ContinuationRanker.DUPLICATE_SIMILARITY);
        assertThat(ContinuationText.jaccard(a, c)).isLessThan(ContinuationRanker.DUPLICATE_SIMILARITY);
        var newer = handoff("h1", "s1", hours(1), a, b);
        var older = handoff("h2", "s2", hours(2), null, c);

        var frontier = ContinuationRanker.rank(ROOT, List.of(newer, older), List.of(), NOW);

        // B joins A's group; C resembles only B, so it stays a candidate of its own.
        assertThat(frontier.candidates())
                .extracting(ContinuationCandidate::basis, ContinuationCandidate::text)
                .containsExactly(
                        tuple(ContinuationBasis.HANDOFF_NEXT_ACTION, a), tuple(ContinuationBasis.HANDOFF_OPEN_LOOP, c));
        assertThat(frontier.candidates().getFirst().sources())
                .extracting(source -> source.capture().eventId() + ":" + source.field())
                .containsExactly("h1:nextAction", "h1:openLoops");
    }

    @Test
    void aMatchingOpenLoopMergesIntoTheRecordedNextStep() {
        var handoff = handoff("h1", "s1", hours(1), "Reproduce the recovery path", "reproduce the recovery path");

        var frontier = ContinuationRanker.rank(ROOT, List.of(handoff), List.of(), NOW);

        assertThat(frontier.candidates()).singleElement().satisfies(candidate -> {
            assertThat(candidate.basis()).isEqualTo(ContinuationBasis.HANDOFF_NEXT_ACTION);
            assertThat(candidate.sources())
                    .extracting(ContinuationSource::field)
                    .containsExactly("nextAction", "openLoops");
        });
    }

    @Test
    void emptyInputHasNoHeadAndNoCandidates() {
        var frontier = ContinuationRanker.rank(ROOT, List.of(), List.of(), NOW);

        assertThat(frontier.empty()).isTrue();
        assertThat(frontier.headAt()).isNull();
        assertThat(frontier.stale()).isFalse();
        assertThat(frontier.candidates()).isEmpty();
    }

    @Test
    void rankingIsDeterministicForTheSameInput() {
        var captures = List.of(
                handoff("h1", "s1", hours(3), "Ship the parser", "Benchmark parsing"),
                handoff("h2", "s2", hours(3), "Write the docs", "Benchmark parsing speed"));

        var first = ContinuationRanker.rank(ROOT, captures, List.of(decision("d1", hours(2), "Pick a license")), NOW);
        var second = ContinuationRanker.rank(
                ROOT,
                List.of(captures.get(1), captures.get(0)),
                List.of(decision("d1", hours(2), "Pick a license")),
                NOW);

        assertThat(second).isEqualTo(first);
        // Equal timestamps break ties by event id, newest first, mirroring the trajectory sort.
        assertThat(first.candidates().getFirst().text()).isEqualTo("Write the docs");
    }
}
