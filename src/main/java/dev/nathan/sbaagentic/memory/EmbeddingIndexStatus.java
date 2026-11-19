package dev.nathan.sbaagentic.memory;

/**
 * Parity between embeddable intent and the stored embeddings and vector index.
 *
 * <p>Event counts cover the embeddable kinds (decision, handoff, observation, idea, evidence). An
 * event whose text is blank is counted as embeddable but is never embedded, so a small
 * {@code missingEvents} figure is not necessarily drift.
 *
 * @param status {@code in_sync}, {@code missing_embeddings}, {@code vector_drift}, or
 *     {@code embedding_disabled}
 * @param embeddingEnabled whether the embedding client is configured on
 * @param model the configured embedding model whose vectors recall uses
 * @param dimensions the configured vector width
 * @param embeddableEvents embeddable event rows
 * @param embeddedEvents events with a current-model embedding
 * @param missingEvents embeddable events with no current-model embedding
 * @param orphanedEmbeddings embeddings whose event or session no longer exists
 * @param otherModelEmbeddings embeddings from another model or width, ignored by recall
 * @param embeddableSessionSummaries sessions with a non-blank summary
 * @param embeddedSessionSummaries summaries with a current-model embedding
 * @param vectorBackend {@code sqlite-vec} or {@code brute-force}
 * @param vectorRows rows in the sqlite-vec index, or {@code null} for brute force, which reads the
 *     canonical embeddings directly
 * @param currentModelEmbeddings canonical embeddings the vector index should mirror
 */
public record EmbeddingIndexStatus(
        String status,
        boolean embeddingEnabled,
        String model,
        int dimensions,
        long embeddableEvents,
        long embeddedEvents,
        long missingEvents,
        long orphanedEmbeddings,
        long otherModelEmbeddings,
        long embeddableSessionSummaries,
        long embeddedSessionSummaries,
        String vectorBackend,
        Long vectorRows,
        long currentModelEmbeddings) {}
