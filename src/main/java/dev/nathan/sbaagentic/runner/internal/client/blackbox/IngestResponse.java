package dev.nathan.sbaagentic.runner.internal.client.blackbox;

public record IngestResponse(
        String eventId,
        String sessionId,
        String source,
        String clientSessionId,
        String eventType,
        boolean indexed) {
}
