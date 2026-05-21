package dev.nathan.sbaagentic.event;

public record IngestResponse(
        String eventId,
        String sessionId,
        String source,
        String clientSessionId,
        String eventType,
        boolean indexed) {
}
