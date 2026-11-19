package dev.nathan.sbaagentic.memory.internal.application;

import dev.nathan.sbaagentic.memory.EmbeddingIndexStatus;
import dev.nathan.sbaagentic.memory.MemoryEmbeddingProperties;
import dev.nathan.sbaagentic.memory.MemoryIndexDiagnostics;
import dev.nathan.sbaagentic.memory.internal.application.port.EmbeddingIndexInspector;
import dev.nathan.sbaagentic.memory.internal.application.port.EmbeddingIndexInspector.Counts;
import org.springframework.stereotype.Service;

@Service
public class MemoryIndexDiagnosticsService implements MemoryIndexDiagnostics {

    private final EmbeddingIndexInspector inspector;
    private final MemoryEmbeddingProperties properties;

    public MemoryIndexDiagnosticsService(EmbeddingIndexInspector inspector, MemoryEmbeddingProperties properties) {
        this.inspector = inspector;
        this.properties = properties;
    }

    @Override
    public EmbeddingIndexStatus embeddingIndex() {
        Counts counts = inspector.count(properties.getModel(), properties.getDimensions());
        long missing = Math.max(0, counts.embeddableEvents() - counts.embeddedEvents());

        return new EmbeddingIndexStatus(
                status(counts, missing),
                properties.isEnabled(),
                properties.getModel(),
                properties.getDimensions(),
                counts.embeddableEvents(),
                counts.embeddedEvents(),
                missing,
                counts.orphanedEmbeddings(),
                counts.otherModelEmbeddings(),
                counts.embeddableSessionSummaries(),
                counts.embeddedSessionSummaries(),
                counts.vectorBackend(),
                counts.vectorRows(),
                counts.currentModelEmbeddings());
    }

    private String status(Counts counts, long missing) {
        // A native index that disagrees with canonical rows returns wrong neighbours: worst first.
        if (counts.vectorRows() != null && counts.vectorRows() != counts.currentModelEmbeddings()) {

            return "vector_drift";
        }
        if (missing == 0) {

            return "in_sync";
        }

        return properties.isEnabled() ? "missing_embeddings" : "embedding_disabled";
    }
}
