package dev.nathan.sbaagentic.memory.internal.application;

import static org.assertj.core.api.Assertions.assertThat;

import dev.nathan.sbaagentic.memory.EmbeddingIndexStatus;
import dev.nathan.sbaagentic.memory.MemoryEmbeddingProperties;
import dev.nathan.sbaagentic.memory.internal.application.port.EmbeddingIndexInspector.Counts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Embedding index status ranks vector drift above missing embeddings")
class MemoryIndexDiagnosticsServiceTest {

    private static EmbeddingIndexStatus status(boolean enabled, Counts counts) {
        MemoryEmbeddingProperties properties = new MemoryEmbeddingProperties();
        properties.setEnabled(enabled);

        return new MemoryIndexDiagnosticsService((model, dimensions) -> counts, properties).embeddingIndex();
    }

    private static Counts counts(long embeddable, long embedded, Long vectorRows, long currentModel) {

        return new Counts(
                embeddable,
                embedded,
                0,
                0,
                0,
                0,
                currentModel,
                vectorRows == null ? "brute-force" : "sqlite-vec",
                vectorRows);
    }

    @Test
    void everyEventEmbeddedAndIndexedIsInSync() {
        EmbeddingIndexStatus status = status(true, counts(10, 10, 10L, 10));

        assertThat(status.status()).isEqualTo("in_sync");
        assertThat(status.missingEvents()).isZero();
        assertThat(status.vectorBackend()).isEqualTo("sqlite-vec");
    }

    @Test
    void missingEmbeddingsDependOnWhetherEmbeddingIsOn() {
        assertThat(status(true, counts(10, 7, null, 7)).status()).isEqualTo("missing_embeddings");
        assertThat(status(false, counts(10, 7, null, 7)).status()).isEqualTo("embedding_disabled");
        assertThat(status(true, counts(10, 7, null, 7)).missingEvents()).isEqualTo(3);
    }

    @Test
    void nativeIndexDisagreeingWithCanonicalRowsIsDriftEvenWhenOtherwiseComplete() {
        assertThat(status(true, counts(10, 10, 9L, 10)).status()).isEqualTo("vector_drift");
        assertThat(status(true, counts(10, 7, 6L, 7)).status()).isEqualTo("vector_drift");
    }

    @Test
    void bruteForceHasNoNativeIndexToDrift() {
        EmbeddingIndexStatus status = status(true, counts(4, 4, null, 99));

        assertThat(status.status()).isEqualTo("in_sync");
        assertThat(status.vectorRows()).isNull();
    }

    @Test
    void missingCountNeverGoesNegative() {
        assertThat(status(true, counts(2, 5, null, 5)).missingEvents()).isZero();
    }
}
