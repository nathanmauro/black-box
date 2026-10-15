package dev.nathan.sbaagentic.memory.internal.domain;

import java.time.Instant;

/** One source listed under a prompt's Sources, with its relative Recall evidence link. */
public record ContinuationLink(
        String eventId, String sessionId, String kind, String field, Instant observedAt) {

    public String href() {

        return ContinuationText.href(sessionId, eventId);
    }
}
