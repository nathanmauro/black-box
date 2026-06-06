package dev.nathan.sbaagentic.ask;

import java.util.List;

public interface MemoryRetriever {

    AskComponentStatus status();

    List<MemoryHit> bm25(String query, int limit);

    List<MemoryHit> knn(float[] embedding, int limit);
}
