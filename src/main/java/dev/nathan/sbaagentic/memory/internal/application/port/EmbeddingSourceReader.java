package dev.nathan.sbaagentic.memory.internal.application.port;

import java.util.List;
import java.util.Map;

public interface EmbeddingSourceReader {

    List<EmbeddingSource> nextBatch(String afterTargetKind, String afterTargetId, int limit);

    record EmbeddingSource(
            String targetKind,
            String targetId,
            String eventType,
            String text,
            Map<String, Object> metadata) {
    }
}
