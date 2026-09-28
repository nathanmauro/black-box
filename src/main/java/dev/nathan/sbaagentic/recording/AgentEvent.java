package dev.nathan.sbaagentic.recording;

import java.time.Instant;
import java.util.Map;

public record AgentEvent(
        String id,
        String sessionId,
        String source,
        String clientSessionId,
        String turnId,
        String eventType,
        String role,
        String text,
        String toolName,
        String toolInputJson,
        String toolOutputJson,
        Map<String, Object> metadata,
        Instant observedAt,
        String humanText) {

    /** Convenience for callers that do not classify human turns ({@code humanText} is {@code null}). */
    public AgentEvent(
            String id,
            String sessionId,
            String source,
            String clientSessionId,
            String turnId,
            String eventType,
            String role,
            String text,
            String toolName,
            String toolInputJson,
            String toolOutputJson,
            Map<String, Object> metadata,
            Instant observedAt) {
        this(
                id,
                sessionId,
                source,
                clientSessionId,
                turnId,
                eventType,
                role,
                text,
                toolName,
                toolInputJson,
                toolOutputJson,
                metadata,
                observedAt,
                null);
    }
}
