package dev.nathan.sbaagentic.platform.internal.adapter.in.sse;

import dev.nathan.sbaagentic.workflow.Task;
import dev.nathan.sbaagentic.workflow.TaskAnnotation;

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
            String observedAt,
            String id,
            String cwd) {
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

    /** A committed task lifecycle change, named for the value of {@code transitionType}. */
    public record TaskChanged(
            Task task,
            String transitionId,
            String transitionType,
            String observedAt) {
    }

    /** A committed task annotation, named SSE event {@code task.note}. */
    public record TaskNoted(Task task, TaskAnnotation annotation, String observedAt) {
    }

    private StreamEvents() {
    }
}
