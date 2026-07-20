package dev.nathan.sbaagentic.memory;

import java.util.List;

public interface MemoryRetrievalOperations {

    MemoryRetrievalStatus status();

    List<MemoryHit> bm25(String query, int limit);

    List<MemoryHit> knn(float[] embedding, int limit);
}
