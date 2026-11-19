package dev.nathan.sbaagentic.project.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class KindTrailerTest {

    @Test
    void findsTheConventionalTrailerLine() {
        assertThat(KindTrailer.presentIn("Because it is simpler.\nKind: architecture | Tests: none | ADR: -"))
                .isTrue();
        assertThat(KindTrailer.presentIn("  kind:data")).isTrue();
        assertThat(KindTrailer.presentIn(null, "Kind: api")).isTrue();
    }

    @Test
    void ignoresMentionsThatAreNotATrailer() {
        assertThat(KindTrailer.presentIn("This kind: of thing")).isFalse();
        assertThat(KindTrailer.presentIn("Kind:")).isFalse();
        assertThat(KindTrailer.presentIn("Kind:   | Tests: x")).isFalse();
        assertThat(KindTrailer.presentIn("Mankind: no")).isFalse();
        assertThat(KindTrailer.presentIn((String) null)).isFalse();
        assertThat(KindTrailer.presentIn()).isFalse();
    }
}
