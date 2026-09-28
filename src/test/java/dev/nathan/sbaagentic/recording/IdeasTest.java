package dev.nathan.sbaagentic.recording;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class IdeasTest {

    @Test
    void originNormalizesCaseSeparatorsAndTheLegacyAside() {
        assertThat(Ideas.normalizeOrigin("nathan-aside")).isEqualTo("human-aside");
        assertThat(Ideas.normalizeOrigin(" Agent_Proposed ")).isEqualTo("agent-proposed");
        assertThat(Ideas.normalizeOrigin("JOINT")).isEqualTo("joint");
        assertThat(Ideas.normalizeOrigin("brainstorm")).isNull();
        assertThat(Ideas.normalizeOrigin(" ")).isNull();
        assertThat(Ideas.normalizeOrigin(null)).isNull();
    }

    @Test
    void statusAcceptsOnlyTheVocabulary() {
        assertThat(Ideas.normalizeStatus("Built Unused")).isEqualTo("built-unused");
        assertThat(Ideas.normalizeStatus("superseded")).isEqualTo("superseded");
        assertThat(Ideas.normalizeStatus("done")).isNull();
    }

    @Test
    void defaultKeySlugsTheRepoBasenameAndTitle() {
        assertThat(Ideas.defaultKey("/Users/example/proj/sba-agentic/", "Black Box: Evidence kind!"))
                .isEqualTo("sba-agentic-black-box-evidence-kind");
        assertThat(Ideas.defaultKey("/other/checkout/sba-agentic", "Black Box: Evidence kind!"))
                .isEqualTo("sba-agentic-black-box-evidence-kind");
        assertThat(Ideas.defaultKey(null, "  Lanes board ")).isEqualTo("lanes-board");
        assertThat(Ideas.defaultKey(null, "!!!")).isEqualTo("idea");
        assertThat(Ideas.defaultKey("/repo", "x".repeat(400))).hasSizeLessThanOrEqualTo(160);
    }
}
