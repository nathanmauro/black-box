package dev.nathan.sbaagentic.project;

import java.time.Instant;
import java.util.Map;

public record ProjectTimelineBlock(
        String id,
        String sourceType,
        String blockType,
        String headline,
        String text,
        String eventType,
        String role,
        String source,
        String clientSessionId,
        String sessionId,
        String sessionTitle,
        String cwd,
        String toolName,
        String toolInputJson,
        String toolOutputJson,
        Map<String, Object> metadata,
        Instant observedAt) {
}
