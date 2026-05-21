package dev.nathan.sbaagentic.event;

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
        Instant observedAt) {
}
