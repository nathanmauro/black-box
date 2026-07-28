package dev.nathan.sbaagentic.memory.internal.application;

import java.time.Instant;
import java.util.Objects;

import dev.nathan.sbaagentic.memory.internal.application.port.EmbeddingStore;
import dev.nathan.sbaagentic.memory.internal.application.port.EmbeddingStore.StoredEmbedding;
import dev.nathan.sbaagentic.memory.internal.application.port.TextEmbedder;
import dev.nathan.sbaagentic.memory.internal.domain.EmbeddableText;
import dev.nathan.sbaagentic.memory.internal.domain.EmbeddingVector;
import dev.nathan.sbaagentic.recording.AgentEvent;
import dev.nathan.sbaagentic.recording.EventRecorded;
import dev.nathan.sbaagentic.recording.EventTypes;
import dev.nathan.sbaagentic.recording.SessionSummaryRecorded;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

@Service
public class EmbeddingIndexer {

    static final String TARGET_EVENT = "event";
    static final String TARGET_SESSION_SUMMARY = "session_summary";

    private static final Logger log = LoggerFactory.getLogger(EmbeddingIndexer.class);

    private final TextEmbedder embedder;
    private final EmbeddingStore store;

    public EmbeddingIndexer(TextEmbedder embedder, EmbeddingStore store) {
        this.embedder = embedder;
        this.store = store;
    }

    @EventListener
    @Order(30)
    public void indexRecordedEvent(EventRecorded recorded) {
        if (recorded == null || !embeddableEventType(recorded.event())) {
            return;
        }
        indexEvent(recorded.event());
    }

    @EventListener
    @Order(30)
    public void indexSessionSummary(SessionSummaryRecorded recorded) {
        if (recorded == null || recorded.session() == null) {
            return;
        }
        indexSessionSummary(recorded.session().id(), recorded.session().summary());
    }

    IndexOutcome indexEvent(AgentEvent event) {
        if (!embeddableEventType(event)) {
            return IndexOutcome.SKIPPED;
        }
        String text = EmbeddableText.forEvent(event);
        return index(TARGET_EVENT, event.id(), text);
    }

    IndexOutcome indexSessionSummary(String sessionId, String summary) {
        return index(TARGET_SESSION_SUMMARY, sessionId, EmbeddableText.forSessionSummary(summary));
    }

    IndexOutcome index(String targetKind, String targetId, String text) {
        try {
            Objects.requireNonNull(targetKind, "targetKind");
            Objects.requireNonNull(targetId, "targetId");
            if (text == null || text.isBlank()) {
                return IndexOutcome.SKIPPED;
            }
            String contentHash = documentContentHash(text);
            if (store.hasCurrentEmbedding(
                    targetKind,
                    targetId,
                    contentHash,
                    embedder.model(),
                    embedder.dimensions())) {
                return IndexOutcome.SKIPPED;
            }
            if (!embedder.available()) {
                return IndexOutcome.SKIPPED;
            }
            EmbeddingVector vector = embedder.embedDocument(text);
            store.upsert(new StoredEmbedding(targetKind, targetId, vector, contentHash, Instant.now()));
            return IndexOutcome.EMBEDDED;
        }
        catch (Exception ex) {
            log.warn("Memory embedding index failed for targetKind={} targetId={}", targetKind, targetId, ex);
            return IndexOutcome.FAILED;
        }
    }

    String model() {
        return embedder.model();
    }

    int dimensions() {
        return embedder.dimensions();
    }

    String documentContentHash(String text) {
        return embedder.documentContentHash(text);
    }

    void shutdown() {
    }

    private static boolean embeddableEventType(AgentEvent event) {
        if (event == null) {
            return false;
        }
        return switch (EventTypes.normalize(event.eventType())) {
            case "decision", "handoff", "observation" -> true;
            default -> false;
        };
    }

    enum IndexOutcome {
        EMBEDDED,
        SKIPPED,
        FAILED
    }
}
