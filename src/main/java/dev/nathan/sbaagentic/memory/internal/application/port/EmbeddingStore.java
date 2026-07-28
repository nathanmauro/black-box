package dev.nathan.sbaagentic.memory.internal.application.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import dev.nathan.sbaagentic.memory.internal.domain.EmbeddingVector;

public interface EmbeddingStore {

    void upsert(StoredEmbedding embedding);

    Optional<String> findHash(String targetKind, String targetId);

    boolean hasCurrentEmbedding(String targetKind, String targetId, String contentHash, String model, int dimensions);

    List<StoredEmbedding> loadAll(String model, int dimensions);

    void deleteFor(String targetKind, String targetId);

    long count();

    record StoredEmbedding(
            String targetKind,
            String targetId,
            EmbeddingVector vector,
            String contentHash,
            Instant embeddedAt) {
    }
}
