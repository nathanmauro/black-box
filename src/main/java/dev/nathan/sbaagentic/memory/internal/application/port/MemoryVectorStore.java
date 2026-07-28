package dev.nathan.sbaagentic.memory.internal.application.port;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import dev.nathan.sbaagentic.memory.internal.domain.EmbeddingVector;

public interface MemoryVectorStore {

    List<ScoredKey> knn(EmbeddingVector query, int k, Predicate<String> keyFilter);

    Map<String, EmbeddingVector> fetchVectors(Collection<String> keys, String model, int dimensions);

    record ScoredKey(String key, double score) {
    }
}
