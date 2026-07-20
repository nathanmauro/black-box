package dev.nathan.sbaagentic.recording.internal.application.port;

import java.time.Instant;

import dev.nathan.sbaagentic.recording.AgentEvent;
import dev.nathan.sbaagentic.recording.AgentSession;
import dev.nathan.sbaagentic.recording.EventIngestRequest;

/** Atomic canonical write port owned by the recording application layer. */
public interface RecordingStore {

    Persisted persistEvent(EventIngestRequest request, Instant observedAt, String title, int titleRank);

    record Persisted(AgentSession session, AgentEvent event) {
    }
}
