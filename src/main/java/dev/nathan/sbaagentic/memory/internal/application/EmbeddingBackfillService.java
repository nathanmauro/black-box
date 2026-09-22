package dev.nathan.sbaagentic.memory.internal.application;

import dev.nathan.sbaagentic.memory.MemoryEmbeddingBackfillRequest;
import dev.nathan.sbaagentic.memory.MemoryEmbeddingBackfillResult;
import dev.nathan.sbaagentic.memory.MemoryEmbeddingOperations;
import dev.nathan.sbaagentic.memory.internal.application.EmbeddingIndexer.IndexOutcome;
import dev.nathan.sbaagentic.memory.internal.application.port.EmbeddingSourceReader;
import dev.nathan.sbaagentic.memory.internal.application.port.EmbeddingSourceReader.EmbeddingSource;
import dev.nathan.sbaagentic.memory.internal.application.port.EmbeddingStore;
import dev.nathan.sbaagentic.memory.internal.domain.EmbeddableText;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class EmbeddingBackfillService implements MemoryEmbeddingOperations {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingBackfillService.class);
    private static final int DEFAULT_BATCH_SIZE = 100;
    private static final int MAX_BATCH_SIZE = 500;
    private static final int DEFAULT_PROGRESS_EVERY = 250;

    private final EmbeddingSourceReader sourceReader;
    private final EmbeddingStore store;
    private final EmbeddingIndexer indexer;

    public EmbeddingBackfillService(
            EmbeddingSourceReader sourceReader, EmbeddingStore store, EmbeddingIndexer indexer) {
        this.sourceReader = sourceReader;
        this.store = store;
        this.indexer = indexer;
    }

    @Override
    public MemoryEmbeddingBackfillResult backfillEmbeddings(MemoryEmbeddingBackfillRequest request) {

        return backfillEmbeddings(request, () -> !Thread.currentThread().isInterrupted());
    }

    MemoryEmbeddingBackfillResult backfillEmbeddings(
            MemoryEmbeddingBackfillRequest request, BooleanSupplier shouldContinue) {
        MemoryEmbeddingBackfillRequest safeRequest = safeRequest(request);
        BackfillCounts counts = new BackfillCounts(safeBatchSize(safeRequest), safeProgressEvery(safeRequest));
        String afterKind = null;
        String afterId = null;
        boolean canceled = false;

        while (true) {
            if (!shouldContinue.getAsBoolean()) {
                canceled = true;
                break;
            }
            List<EmbeddingSource> batch = sourceReader.nextBatch(afterKind, afterId, counts.batchSize);
            if (batch.isEmpty()) {
                break;
            }
            for (EmbeddingSource source : batch) {
                afterKind = source.targetKind();
                afterId = source.targetId();
                if (!shouldContinue.getAsBoolean()) {
                    canceled = true;
                    break;
                }
                process(source, safeRequest.apply(), counts);
                logProgress(counts);
            }
            if (canceled || batch.size() < counts.batchSize) {
                break;
            }
        }
        log.info(
                "Memory embedding backfill finished apply={} scanned={} candidates={} skipped={} embedded={} failed={} canceled={}",
                safeRequest.apply(),
                counts.scanned,
                counts.candidates,
                counts.skipped,
                counts.embedded,
                counts.failed,
                canceled);

        return new MemoryEmbeddingBackfillResult(
                safeRequest.apply(),
                counts.batchSize,
                counts.scanned,
                counts.candidates,
                counts.skipped,
                counts.embedded,
                counts.failed,
                canceled);
    }

    private static MemoryEmbeddingBackfillRequest safeRequest(MemoryEmbeddingBackfillRequest request) {
        if (request == null) {

            return new MemoryEmbeddingBackfillRequest(false, DEFAULT_BATCH_SIZE, DEFAULT_PROGRESS_EVERY);
        }

        return request;
    }

    private void process(EmbeddingSource source, boolean apply, BackfillCounts counts) {
        counts.scanned++;
        String text = textFor(source);
        if (text.isBlank()) {
            counts.skipped++;

            return;
        }
        String contentHash = indexer.documentContentHash(text);
        try {
            if (store.hasCurrentEmbedding(
                    source.targetKind(), source.targetId(), contentHash, indexer.model(), indexer.dimensions())) {
                counts.skipped++;

                return;
            }
        } catch (Exception ex) {
            counts.failed++;
            log.warn(
                    "Memory embedding backfill current-embedding lookup failed for targetKind={} targetId={}",
                    source.targetKind(),
                    source.targetId(),
                    ex);

            return;
        }
        counts.candidates++;
        if (!apply) {

            return;
        }
        IndexOutcome outcome = indexer.index(source.targetKind(), source.targetId(), text);
        switch (outcome) {
            case EMBEDDED -> counts.embedded++;
            case SKIPPED -> counts.skipped++;
            case FAILED -> counts.failed++;
        }
    }

    private static String textFor(EmbeddingSource source) {
        if (EmbeddingIndexer.TARGET_EVENT.equals(source.targetKind())) {

            return EmbeddableText.forEvent(source.eventType(), source.text(), source.metadata());
        }
        if (EmbeddingIndexer.TARGET_SESSION_SUMMARY.equals(source.targetKind())) {

            return EmbeddableText.forSessionSummary(source.text());
        }

        return "";
    }

    private void logProgress(BackfillCounts counts) {
        if (counts.progressEvery <= 0 || counts.scanned % counts.progressEvery != 0) {

            return;
        }
        log.info(
                "Memory embedding backfill progress scanned={} candidates={} skipped={} embedded={} failed={}",
                counts.scanned,
                counts.candidates,
                counts.skipped,
                counts.embedded,
                counts.failed);
    }

    private static int safeBatchSize(MemoryEmbeddingBackfillRequest request) {
        if (request == null || request.batchSize() <= 0) {

            return DEFAULT_BATCH_SIZE;
        }

        return Math.min(request.batchSize(), MAX_BATCH_SIZE);
    }

    private static int safeProgressEvery(MemoryEmbeddingBackfillRequest request) {
        if (request == null || request.progressEvery() <= 0) {

            return DEFAULT_PROGRESS_EVERY;
        }

        return request.progressEvery();
    }

    private static final class BackfillCounts {
        private final int batchSize;
        private final int progressEvery;
        private long scanned;
        private long candidates;
        private long skipped;
        private long embedded;
        private long failed;

        private BackfillCounts(int batchSize, int progressEvery) {
            this.batchSize = batchSize;
            this.progressEvery = progressEvery;
        }
    }
}
