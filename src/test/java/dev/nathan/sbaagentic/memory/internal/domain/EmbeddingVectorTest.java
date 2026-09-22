package dev.nathan.sbaagentic.memory.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class EmbeddingVectorTest {

    @Test
    void blobRoundTripPreservesValuesExactly() {
        EmbeddingVector vector = new EmbeddingVector("nomic", new float[] {1.25f, -2.5f, 0.0f, Float.NaN});

        EmbeddingVector roundTripped = EmbeddingVector.fromBlob("nomic", vector.toBlob());

        assertThat(roundTripped.model()).isEqualTo("nomic");
        assertThat(roundTripped.values()).containsExactly(vector.values());
    }

    @Test
    void cosineSimilarityMatchesHandComputedFixtures() {
        EmbeddingVector x = new EmbeddingVector("nomic", new float[] {1.0f, 0.0f});
        EmbeddingVector y = new EmbeddingVector("nomic", new float[] {0.0f, 1.0f});

        assertThat(x.cosineSimilarity(x)).isEqualTo(1.0);
        assertThat(x.cosineSimilarity(y)).isEqualTo(0.0);
    }

    @Test
    void cosineSimilarityRejectsDimensionMismatch() {
        EmbeddingVector left = new EmbeddingVector("nomic", new float[] {1.0f, 2.0f});
        EmbeddingVector right = new EmbeddingVector("nomic", new float[] {1.0f});

        assertThatIllegalArgumentException()
                .isThrownBy(() -> left.cosineSimilarity(right))
                .withMessageContaining("dimensions");
    }

    @Test
    void normalizedReturnsUnitLengthCopyAndLeavesZeroVectorUnchanged() {
        EmbeddingVector vector = new EmbeddingVector("nomic", new float[] {3.0f, 4.0f});
        EmbeddingVector normalized = vector.normalized();

        assertThat(normalized).isNotSameAs(vector);
        assertThat(normalized.values()).containsExactly(0.6f, 0.8f);

        EmbeddingVector zero = new EmbeddingVector("nomic", new float[] {0.0f, 0.0f});

        assertThat(zero.normalized()).isSameAs(zero);
    }

    @Test
    void equalityAndHashCodeUseModelAndVectorContents() {
        EmbeddingVector left = new EmbeddingVector("nomic", new float[] {1.0f, 2.0f});
        EmbeddingVector right = new EmbeddingVector("nomic", new float[] {1.0f, 2.0f});
        EmbeddingVector differentValue = new EmbeddingVector("nomic", new float[] {1.0f, 2.1f});
        EmbeddingVector differentModel = new EmbeddingVector("other", new float[] {1.0f, 2.0f});

        assertThat(left).isEqualTo(right);
        assertThat(left.hashCode()).isEqualTo(right.hashCode());
        assertThat(left).isNotEqualTo(differentValue);
        assertThat(left).isNotEqualTo(differentModel);
    }

    @Test
    void oddLengthBlobIsRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> EmbeddingVector.fromBlob("nomic", new byte[] {1, 2, 3}))
                .withMessageContaining("multiple of 4");
    }

    @Test
    void contentHashIsStableAndPreservesWhitespace() {
        assertThat(EmbeddingVector.contentHash("memory text"))
                .isEqualTo(EmbeddingVector.contentHash("memory text"))
                .isNotEqualTo(EmbeddingVector.contentHash("memory text "));
    }
}
