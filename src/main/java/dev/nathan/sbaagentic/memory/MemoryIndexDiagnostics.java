package dev.nathan.sbaagentic.memory;

/** Read-only health of the memory module's embedding and vector indexes. */
public interface MemoryIndexDiagnostics {

    EmbeddingIndexStatus embeddingIndex();
}
