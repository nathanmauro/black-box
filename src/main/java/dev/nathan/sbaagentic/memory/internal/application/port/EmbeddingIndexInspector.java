package dev.nathan.sbaagentic.memory.internal.application.port;

public interface EmbeddingIndexInspector {

    /** Raw counts for one embedding model and width; the caller derives the status. */
    Counts count(String model, int dimensions);

    record Counts(
            long embeddableEvents,
            long embeddedEvents,
            long orphanedEmbeddings,
            long otherModelEmbeddings,
            long embeddableSessionSummaries,
            long embeddedSessionSummaries,
            long currentModelEmbeddings,
            String vectorBackend,
            Long vectorRows) {}
}
