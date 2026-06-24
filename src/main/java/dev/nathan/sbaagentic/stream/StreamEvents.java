package dev.nathan.sbaagentic.stream;

/**
 * Lightweight payloads pushed over the {@code /api/stream} Server-Sent Events channel. These carry
 * only summary fields (never full event bodies) so the live feed stays cheap; the UI fetches detail
 * on demand.
 */
public final class StreamEvents {

    /** A newly persisted event, named SSE event {@code event.appended}. */
    public record EventAppended(
            String sessionId,
            String source,
            String eventType,
            String toolName,
            String title,
            String observedAt) {
    }

    /** The owning session's latest state after an append, named SSE event {@code session.updated}. */
    public record SessionUpdated(
            String sessionId,
            String source,
            String title,
            String cwd,
            long eventCount,
            String lastSeenAt) {
    }

    private StreamEvents() {
    }
}
