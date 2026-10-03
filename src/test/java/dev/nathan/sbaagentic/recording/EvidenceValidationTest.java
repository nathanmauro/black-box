package dev.nathan.sbaagentic.recording;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class EvidenceValidationTest {
    @Test
    void refsNormalizeAllFormsAndRejectBadValuesWithFieldNames() {
        assertThat(EvidenceRefs.normalize(" IDEA: Key ", "supports")).isEqualTo("idea:Key");
        assertThat(EvidenceRefs.normalize(" EVENT: FA35F02B ", "supports")).isEqualTo("event:fa35f02b");
        assertThat(EvidenceRefs.normalize(" FA35F02B ", "supports")).isEqualTo("event:fa35f02b");
        assertThat(EvidenceRefs.normalizeList(List.of("event:FA35F02B", "fa35f02b"), "supports"))
                .containsExactly("event:fa35f02b");
        assertThat(EvidenceRefs.normalizeList(List.of(), "supports")).isNull();
        assertThatThrownBy(() -> EvidenceRefs.normalize("tem-pilot", "supports"))
                .hasMessageContaining("supports entry 'tem-pilot'")
                .hasMessageContaining("idea:<ideaKey> or event:<eventId>");
        assertThatThrownBy(() -> EvidenceRefs.normalize("event:invalid", "refutes"))
                .hasMessageContaining("refutes entry 'event:invalid'");
        assertThatThrownBy(() -> EvidenceRefs.normalize("bad target", "target"))
                .hasMessageContaining("target entry 'bad target'");
        assertThatThrownBy(() -> EvidenceRefs.checkDisjoint(
                        EvidenceRefs.normalizeList(List.of("event:FA35F02B"), "supports"),
                        EvidenceRefs.normalizeList(List.of("fa35f02b"), "refutes")))
                .hasMessageContaining("both supports and refutes");
        assertThatThrownBy(() -> EvidenceRefs.normalizeList(java.util.Collections.nCopies(51, "idea:key"), "supports"))
                .hasMessageContaining("supports may contain at most 50");
    }

    @Test
    void lanesValidateEffectiveHomeRangeAndCap() {
        assertThat(Lanes.validate("home", "/repo", List.of(new LaneListing(" other ", 0.8))))
                .containsExactly(new LaneListing("other", 0.8));
        assertThat(Lanes.validate(null, "/repo", List.of())).isNull();
        assertThatThrownBy(() -> Lanes.validate("Home", "/repo", List.of(new LaneListing(" home ", 0.2))))
                .hasMessageContaining("home lane");
        assertThatThrownBy(() -> Lanes.validate(null, " /repo ", List.of(new LaneListing(" /REPO ", 0.2))))
                .hasMessageContaining("home lane");
        assertThatThrownBy(() -> Lanes.validate(
                        null, null, List.of(new LaneListing("Other", 0.3), new LaneListing(" other ", 0.4))))
                .hasMessageContaining("unique");
        assertThatThrownBy(() -> Lanes.validate(null, null, List.of(new LaneListing("x", null))))
                .hasMessageContaining("score is required for project 'x'");
        assertThatThrownBy(() -> Lanes.validate(null, null, List.of(new LaneListing("x", Double.NaN))))
                .hasMessageContaining("project 'x'")
                .hasMessageContaining("NaN");
        assertThatThrownBy(() -> Lanes.validate(null, null, List.of(new LaneListing("x", 1.1))))
                .hasMessageContaining("project 'x'")
                .hasMessageContaining("1.1");
        List<LaneListing> distinct = IntStream.range(0, 21)
                .mapToObj(index -> new LaneListing("lane-" + index, 0.2))
                .toList();
        assertThatThrownBy(() -> Lanes.validate(null, null, distinct)).hasMessageContaining("at most 20");
    }

    @Test
    void viewDropsSecondaryLaneThatBecameTheHomeLane() {
        assertThat(Lanes.withoutHome("  Board ", List.of(new LaneListing("board", 0.8), new LaneListing("Other", 0.3))))
                .containsExactly(new LaneListing("Other", 0.3));
    }
}
