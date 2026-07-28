package dev.nathan.sbaagentic.memory.internal.application.port;

import dev.nathan.sbaagentic.memory.internal.domain.EmbeddingVector;

public interface TextEmbedder {

    EmbeddingVector embedDocument(String text);

    EmbeddingVector embedQuery(String text);

    String documentContentHash(String text);

    boolean available();

    String model();

    int dimensions();
}
