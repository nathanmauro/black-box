package dev.nathan.sbaagentic.recording;

import java.util.List;

/** One newest-first page from a session's recorded events plus transcript message enrichment. */
public record SessionTranscriptResponse(
        String sessionId,
        boolean available,
        boolean complete,
        String reason,
        int limit,
        long count,
        List<AgentEvent> events,
        String nextBefore) {
}
