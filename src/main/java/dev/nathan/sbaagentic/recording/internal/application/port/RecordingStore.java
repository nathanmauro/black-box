package dev.nathan.sbaagentic.recording.internal.application.port;

import java.time.Instant;

import dev.nathan.sbaagentic.recording.AgentEvent;
import dev.nathan.sbaagentic.recording.AgentSession;
import dev.nathan.sbaagentic.recording.EventIngestRequest;

/** Atomic canonical write port owned by the recording application layer. */
public interface RecordingStore {

    Persisted persistEvent(EventIngestRequest request, Instant observedAt, String title, int titleRank);

    default IdempotentPersisted persistIdempotentEvent(
            String captureId, String requestHash, EventIngestRequest request,
            Instant observedAt, String title, int titleRank) {
        throw new UnsupportedOperationException("Idempotent capture is not supported by this store.");
    }

    record IdempotentPersisted(Persisted persisted, boolean replayed) {
    }

    record Persisted(AgentSession session, AgentEvent event) {
    }
}
