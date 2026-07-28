package dev.nathan.sbaagentic.memory;

public record MemoryEmbeddingBackfillResult(
        boolean apply,
        int batchSize,
        long scanned,
        long candidates,
        long skipped,
        long embedded,
        long failed,
        boolean canceled) {
}
