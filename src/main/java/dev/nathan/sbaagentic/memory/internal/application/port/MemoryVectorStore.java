package dev.nathan.sbaagentic.memory.internal.application.port;

import java.util.List;
import java.util.function.Predicate;

import dev.nathan.sbaagentic.memory.internal.domain.EmbeddingVector;

public interface MemoryVectorStore {

    List<ScoredKey> knn(EmbeddingVector query, int k, Predicate<String> keyFilter);

    record ScoredKey(String key, double score) {
    }
}
