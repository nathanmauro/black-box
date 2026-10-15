package dev.nathan.sbaagentic.memory.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class ContinuationTextTest {

    @Test
    void candidateIdsMatchThePythonPreviewHashOverTheJsonPair() {
        // Expected values computed with blackbox_linear.py's json.dumps([...], ensure_ascii=True) rule.
        assertThat(ContinuationText.candidateId("/tmp/fx-alpha", "Reproduce the recovery path"))
                .isEqualTo("bbc1-104d185173789cbe307fcab8");
        assertThat(ContinuationText.candidateId("/tmp/fx-alpha", "  Reproduce\tthe   RECOVERY path \n"))
                .isEqualTo("bbc1-104d185173789cbe307fcab8");
        assertThat(ContinuationText.candidateId("/repos/café", "Straße prüfen \"quoted\" \\ back 😀"))
                .isEqualTo("bbc1-c53a44936b1f7656a12b5ff9");
        assertThat(ContinuationText.candidateId("/r", "x y z\u001cw")).isEqualTo("bbc1-c8727679f6819a6af06ac952");
        assertThat(ContinuationText.jsonPair("/repos/café", "a\"b\\c\n😀"))
                .isEqualTo("[\"/repos/caf\\u00e9\", \"a\\\"b\\\\c\\n\\ud83d\\ude00\"]");
        assertThat(ContinuationText.candidateId("/tmp/other", "Reproduce the recovery path"))
                .isNotEqualTo("bbc1-104d185173789cbe307fcab8");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "None",
                "N/A",
                "n/a.",
                "NA",
                "No action needed",
                "no further open loops",
                "nothing",
                "Done.",
                "done—see PR",
                "Completed: x",
                "None of the above is done",
                "No open loop"
            })
    void noActionMatchesThePythonPattern(String text) {
        assertThat(ContinuationText.noAction(ContinuationText.collapse(text))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Donezo", "Nothingness", "no actions", "Reproduce the recovery path", "Ship it"})
    void ordinaryTextIsNotNoAction(String text) {
        assertThat(ContinuationText.noAction(ContinuationText.collapse(text))).isFalse();
    }

    @Test
    void literalUsesALongerFenceThanAnyBacktickRun() {
        assertThat(ContinuationText.literal("a ``` b ```` c")).isEqualTo("`````text\na ``` b ```` c\n`````");
        assertThat(ContinuationText.literal("plain")).isEqualTo("```text\nplain\n```");
    }

    @ParameterizedTest
    @CsvSource({
        "Verify recovery before shared access, verify RECOVERY before shared access!, 1.0",
        "Reproduce the recovery path, Reproduce the recovery path before shared access, 0.5714285714285714",
        "Ship the parser, Write the docs, 0.2",
        "Ship parser, Write docs, 0.0",
        "Close NAT-231, Close NAT-232, 0.3333333333333333",
        "Merge the worktree slice 93cb26eb-b67d-48e7-bb68-16733cdad90d, "
                + "Reproduce the recovery path 93cb26eb-b67d-48e7-bb68-16733cdad90d, 0.25",
        "Add tests for src/main/java/dev/app/RecallService.java, "
                + "Refactor src/main/java/dev/app/RecallService.java, 0.2"
    })
    void jaccardCountsEachWordOnceSoSharedIdentifiersDoNotMergeActions(String left, String right, double expected) {
        assertThat(ContinuationText.jaccard(left, right)).isEqualTo(expected);
    }

    @Test
    void collapseMatchesPythonSplitAndDropsControls() {
        assertThat(ContinuationText.collapse("  a  b\n c\u0000d  ")).isEqualTo("a b cd");
        assertThat(ContinuationText.collapse(null)).isEmpty();
    }

    @Test
    void clipNeverSplitsASurrogatePair() {
        String text = "ab😀cd";
        assertThat(ContinuationText.clip(text, 3)).isEqualTo("ab");
        assertThat(ContinuationText.clip(text, 4)).isEqualTo("ab😀");
        assertThat(ContinuationText.clip(text, 10)).isEqualTo(text);
    }

    @Test
    void hrefMatchesTheRecallEvidenceLinkShape() {
        assertThat(ContinuationText.href("s1", "e1")).isEqualTo("/?view=browse&session=s1&event=e1&project=");
    }

    @Test
    void agesAreWholeDaysAndNeverNegative() {
        Instant now = Instant.parse("2026-10-15T12:00:00Z");
        assertThat(ContinuationText.ageDays(Instant.parse("2026-10-13T11:00:00Z"), now))
                .isEqualTo(2);
        assertThat(ContinuationText.ageDays(now.plusSeconds(60), now)).isZero();
        assertThat(ContinuationText.ago(0)).isEqualTo("less than a day ago");
        assertThat(ContinuationText.ago(1)).isEqualTo("1 day ago");
        assertThat(ContinuationText.ago(9)).isEqualTo("9 days ago");
    }
}
