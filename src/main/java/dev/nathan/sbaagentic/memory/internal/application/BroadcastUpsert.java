package dev.nathan.sbaagentic.memory.internal.application;

/**
 * A request to add or refresh one broadcast. {@code repo} is the working directory or a registered
 * project alias. {@code ttlHours} omitted uses the kind's default (72 hours for hazards, sticky for
 * invariants and tentacles); {@code 0} makes it sticky. {@code followUpAt} is an ISO-8601 instant,
 * offset date-time, or date.
 */
public record BroadcastUpsert(
        String source,
        String clientSessionId,
        String repo,
        String key,
        String kind,
        String message,
        String severity,
        Integer ttlHours,
        String followUpAt) {}
