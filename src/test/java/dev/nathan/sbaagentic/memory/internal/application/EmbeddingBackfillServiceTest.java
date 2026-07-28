package dev.nathan.sbaagentic.memory.internal.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import dev.nathan.sbaagentic.memory.MemoryEmbeddingBackfillRequest;
import dev.nathan.sbaagentic.memory.internal.application.port.EmbeddingSourceReader;
import dev.nathan.sbaagentic.memory.internal.application.port.EmbeddingSourceReader.EmbeddingSource;
import dev.nathan.sbaagentic.memory.internal.application.port.EmbeddingStore;
import dev.nathan.sbaagentic.memory.internal.application.port.EmbeddingStore.StoredEmbedding;
import dev.nathan.sbaagentic.memory.internal.application.port.TextEmbedder;
import dev.nathan.sbaagentic.memory.internal.domain.EmbeddingVector;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EmbeddingBackfillServiceTest {

    private final FakeEmbeddingSourceReader sourceReader = new FakeEmbeddingSourceReader();
    private final FakeEmbeddingStore store = new FakeEmbeddingStore();
    private final RecordingTextEmbedder embedder = new RecordingTextEmbedder();
    private final EmbeddingIndexer indexer = new EmbeddingIndexer(embedder, store);
    private final EmbeddingBackfillService service = new EmbeddingBackfillService(sourceReader, store, indexer);

    @AfterEach
    void shutdownIndexer() {
        indexer.shutdown();
    }

    @Test
    void dryRunWritesNothingAndReportsAccurateCount() {
        sourceReader.sources(source("event", "event-1", "Decision", "already indexed"),
                source("event", "event-2", "Decision", "needs indexing"),
                source("session_summary", "session-1", null, "summary needs indexing"));
        store.upsert(stored("event", "event-1", EmbeddingVector.contentHash("already indexed")));

        var result = service.backfillEmbeddings(new MemoryEmbeddingBackfillRequest(false, 2, 1));

        assertThat(result.apply()).isFalse();
        assertThat(result.scanned()).isEqualTo(3);
        assertThat(result.skipped()).isEqualTo(1);
        assertThat(result.candidates()).isEqualTo(2);
        assertThat(result.embedded()).isZero();
        assertThat(result.failed()).isZero();
        assertThat(store.count()).isEqualTo(1);
        assertThat(embedder.embeddedTargetTexts()).isEmpty();
    }

    @Test
    void applyIsIdempotentOnSecondRun() {
        sourceReader.sources(source("event", "event-1", "Decision", "first decision"),
                source("session_summary", "session-1", null, "first summary"));

        var first = service.backfillEmbeddings(new MemoryEmbeddingBackfillRequest(true, 10, 10));
        var second = service.backfillEmbeddings(new MemoryEmbeddingBackfillRequest(true, 10, 10));

        assertThat(first.candidates()).isEqualTo(2);
        assertThat(first.embedded()).isEqualTo(2);
        assertThat(second.candidates()).isZero();
        assertThat(second.skipped()).isEqualTo(2);
        assertThat(second.embedded()).isZero();
        assertThat(store.count()).isEqualTo(2);
        assertThat(embedder.embeddedTargetTexts()).containsExactly("first decision", "first summary");
    }

    @Test
    void sameContentHashWithDifferentModelOrDimensionsIsReembedded() {
        sourceReader.sources(
                source("event", "event-1", "Decision", "same decision"),
                source("event", "event-2", "Decision", "same dimensions changed"));
        store.upsert(stored("event", "event-1", "old-model", new float[] { 1.0f, 0.0f },
                EmbeddingVector.contentHash("same decision")));
        store.upsert(stored("event", "event-2", "test-model", new float[] { 1.0f },
                EmbeddingVector.contentHash("same dimensions changed")));

        var result = service.backfillEmbeddings(new MemoryEmbeddingBackfillRequest(true, 10, 10));

        assertThat(result.candidates()).isEqualTo(2);
        assertThat(result.embedded()).isEqualTo(2);
        assertThat(store.loadAll("test-model", 2))
                .extracting(StoredEmbedding::targetId)
                .containsExactlyInAnyOrder("event-1", "event-2");
        assertThat(store.loadAll("old-model", 2)).isEmpty();
        assertThat(store.loadAll("test-model", 1)).isEmpty();
    }

    @Test
    void resumeAfterCancellationEmbedsOnlyTheRemainder() {
        sourceReader.sources(source("event", "event-1", "Decision", "first decision"),
                source("event", "event-2", "Observation", "second observation"),
                source("session_summary", "session-1", null, "third summary"));
        AtomicInteger checks = new AtomicInteger();

        var interrupted = service.backfillEmbeddings(
                new MemoryEmbeddingBackfillRequest(true, 10, 10),
                () -> checks.incrementAndGet() <= 2);

        assertThat(interrupted.canceled()).isTrue();
        assertThat(interrupted.embedded()).isEqualTo(1);
        assertThat(store.count()).isEqualTo(1);
        embedder.clearTexts();

        var resumed = service.backfillEmbeddings(new MemoryEmbeddingBackfillRequest(true, 10, 10));

        assertThat(resumed.canceled()).isFalse();
        assertThat(resumed.skipped()).isEqualTo(1);
        assertThat(resumed.embedded()).isEqualTo(2);
        assertThat(store.count()).isEqualTo(3);
        assertThat(embedder.embeddedTargetTexts()).containsExactly("second observation", "third summary");
    }

    private static EmbeddingSource source(String targetKind, String targetId, String eventType, String text) {
        return new EmbeddingSource(targetKind, targetId, eventType, text, Map.of());
    }

    private static StoredEmbedding stored(String targetKind, String targetId, String contentHash) {
        return stored(targetKind, targetId, "test-model", new float[] { 1.0f, 0.0f }, contentHash);
    }

    private static StoredEmbedding stored(
            String targetKind, String targetId, String model, float[] values, String contentHash) {
        return new StoredEmbedding(
                targetKind,
                targetId,
                new EmbeddingVector(model, values),
                contentHash,
                Instant.parse("2026-07-28T12:00:00Z"));
    }

    private static final class FakeEmbeddingSourceReader implements EmbeddingSourceReader {

        private List<EmbeddingSource> sources = List.of();

        void sources(EmbeddingSource... sources) {
            this.sources = List.of(sources).stream()
                    .sorted((left, right) -> {
                        int kind = left.targetKind().compareTo(right.targetKind());
                        return kind != 0 ? kind : left.targetId().compareTo(right.targetId());
                    })
                    .toList();
        }

        @Override
        public List<EmbeddingSource> nextBatch(String afterTargetKind, String afterTargetId, int limit) {
            String afterKind = afterTargetKind == null ? "" : afterTargetKind;
            String afterId = afterTargetId == null ? "" : afterTargetId;
            return sources.stream()
                    .filter(source -> source.targetKind().compareTo(afterKind) > 0
                            || (source.targetKind().equals(afterKind)
                                    && source.targetId().compareTo(afterId) > 0))
                    .limit(limit)
                    .toList();
        }
    }

    private static final class FakeEmbeddingStore implements EmbeddingStore {

        private final Map<String, StoredEmbedding> embeddings = new HashMap<>();

        @Override
        public void upsert(StoredEmbedding embedding) {
            embeddings.put(key(embedding.targetKind(), embedding.targetId()), embedding);
        }

        @Override
        public Optional<String> findHash(String targetKind, String targetId) {
            StoredEmbedding embedding = embeddings.get(key(targetKind, targetId));
            return embedding == null ? Optional.empty() : Optional.of(embedding.contentHash());
        }

        @Override
        public boolean hasCurrentEmbedding(
                String targetKind, String targetId, String contentHash, String model, int dimensions) {
            StoredEmbedding embedding = embeddings.get(key(targetKind, targetId));
            return embedding != null
                    && embedding.contentHash().equals(contentHash)
                    && embedding.vector().model().equals(model)
                    && embedding.vector().values().length == dimensions;
        }

        @Override
        public List<StoredEmbedding> loadAll(String model, int dimensions) {
            return embeddings.values().stream()
                    .filter(embedding -> embedding.vector().model().equals(model))
                    .filter(embedding -> embedding.vector().values().length == dimensions)
                    .toList();
        }

        @Override
        public void deleteFor(String targetKind, String targetId) {
            embeddings.remove(key(targetKind, targetId));
        }

        @Override
        public long count() {
            return embeddings.size();
        }

        private static String key(String targetKind, String targetId) {
            return targetKind + ":" + targetId;
        }
    }

    private static final class RecordingTextEmbedder implements TextEmbedder {

        private final List<String> embeddedTargetTexts = new ArrayList<>();

        @Override
        public EmbeddingVector embedDocument(String text) {
            return embed(text);
        }

        @Override
        public EmbeddingVector embedQuery(String text) {
            return embed(text);
        }

        @Override
        public String documentContentHash(String text) {
            return EmbeddingVector.contentHash(text);
        }

        private EmbeddingVector embed(String text) {
            embeddedTargetTexts.add(text);
            return new EmbeddingVector("test-model", new float[] { 1.0f, embeddedTargetTexts.size() });
        }

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public String model() {
            return "test-model";
        }

        @Override
        public int dimensions() {
            return 2;
        }

        List<String> embeddedTargetTexts() {
            return List.copyOf(embeddedTargetTexts);
        }

        void clearTexts() {
            embeddedTargetTexts.clear();
        }
    }
}
