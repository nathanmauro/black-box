package dev.nathan.sbaagentic.memory;

public record MemoryEmbeddingBackfillRequest(
        boolean apply,
        int batchSize,
        int progressEvery) {
}
