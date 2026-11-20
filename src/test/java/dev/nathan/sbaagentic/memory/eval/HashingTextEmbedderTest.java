package dev.nathan.sbaagentic.memory.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import dev.nathan.sbaagentic.memory.internal.application.TextEmbeddingUnavailable;
import dev.nathan.sbaagentic.memory.internal.domain.EmbeddingVector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Benchmark hashing embedder is deterministic, normalized, and lexicon-aware")
class HashingTextEmbedderTest {

    private final HashingTextEmbedder embedder = new HashingTextEmbedder(SyntheticRecallCorpus.LEXICON);

    @Test
    void sameTextAlwaysEmbedsToTheSameUnitVector() {
        float[] first = embedder.vectorFor("raise the busy timeout for sqlite writers");
        float[] second = new HashingTextEmbedder(SyntheticRecallCorpus.LEXICON)
                .vectorFor("raise the busy timeout for sqlite writers");

        assertThat(first).containsExactly(second);
        assertThat(first).hasSize(HashingTextEmbedder.DIMENSIONS);
        assertThat(norm(first)).isCloseTo(1.0, within(1e-5));
    }

    @Test
    void paraphraseIsCloserThanAHardNegative() {
        EmbeddingVector query = embedder.embedQuery("why do concurrent database writes stall");
        EmbeddingVector relevant = embedder.embedDocument(
                "raise the busy timeout for sqlite writers. Concurrent writers hit SQLITE_BUSY lock contention.");
        EmbeddingVector distractor = embedder.embedDocument("raise the timeout on the release checklist page review");

        assertThat(query.cosineSimilarity(relevant)).isGreaterThan(0.61);
        assertThat(query.cosineSimilarity(relevant)).isGreaterThan(query.cosineSimilarity(distractor));
    }

    @Test
    void blankTextStillHasADirection() {
        assertThat(norm(embedder.vectorFor(""))).isCloseTo(1.0, within(1e-6));
        assertThat(norm(embedder.vectorFor(null))).isCloseTo(1.0, within(1e-6));
    }

    @Test
    void switchedOffEmbedderReportsUnavailableAndRefusesToEmbed() {
        HashingTextEmbedder offline = new HashingTextEmbedder(SyntheticRecallCorpus.LEXICON);
        offline.available(false);

        assertThat(offline.available()).isFalse();
        assertThatThrownBy(() -> offline.embedQuery("anything")).isInstanceOf(TextEmbeddingUnavailable.class);
    }

    @Test
    void stemmerFoldsPluralsAndTenses() {
        assertThat(HashingTextEmbedder.stem("writers")).isEqualTo("writer");
        assertThat(HashingTextEmbedder.stem("retries")).isEqualTo("retry");
        assertThat(HashingTextEmbedder.stem("interfering")).isEqualTo("interfer");
        assertThat(HashingTextEmbedder.stem("signed")).isEqualTo("sign");
        assertThat(HashingTextEmbedder.stem("class")).isEqualTo("class");
    }

    private static double norm(float[] vector) {
        double sum = 0.0;
        for (float value : vector) {
            sum += value * value;
        }

        return Math.sqrt(sum);
    }
}
