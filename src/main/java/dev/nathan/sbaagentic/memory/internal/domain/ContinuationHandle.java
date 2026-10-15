package dev.nathan.sbaagentic.memory.internal.domain;

import java.time.Instant;

/** A server-side evidence handle such as {@code E1}; the model sees only the handle, never the event ID. */
public record ContinuationHandle(String handle, String eventId, String sessionId, String kind, Instant observedAt) {

    public String href() {

        return ContinuationText.href(sessionId, eventId);
    }
}
