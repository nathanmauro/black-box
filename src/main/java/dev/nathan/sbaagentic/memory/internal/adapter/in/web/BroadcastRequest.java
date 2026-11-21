package dev.nathan.sbaagentic.memory.internal.adapter.in.web;

import dev.nathan.sbaagentic.memory.internal.application.BroadcastUpsert;

/** Body for {@code PUT|POST /api/broadcasts}. Validation lives in the application service. */
public record BroadcastRequest(
        String source,
        String clientSessionId,
        String repo,
        String key,
        String kind,
        String message,
        String severity,
        Integer ttlHours,
        String followUpAt) {

    BroadcastUpsert toCommand() {

        return new BroadcastUpsert(source, clientSessionId, repo, key, kind, message, severity, ttlHours, followUpAt);
    }
}
