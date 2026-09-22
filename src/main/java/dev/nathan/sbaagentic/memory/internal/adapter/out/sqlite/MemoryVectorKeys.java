package dev.nathan.sbaagentic.memory.internal.adapter.out.sqlite;

import dev.nathan.sbaagentic.memory.internal.application.port.EmbeddingStore.StoredEmbedding;

final class MemoryVectorKeys {

    private MemoryVectorKeys() {}

    static String key(StoredEmbedding embedding) {

        return key(embedding.targetKind(), embedding.targetId());
    }

    static String key(String targetKind, String targetId) {

        return targetKind + ":" + targetId;
    }
}
