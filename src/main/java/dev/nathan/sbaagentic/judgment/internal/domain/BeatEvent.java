package dev.nathan.sbaagentic.judgment.internal.domain;

import dev.nathan.sbaagentic.recording.AgentEvent;
import java.time.Instant;
import java.util.Map;

public record BeatEvent(
        String id,
        String sessionId,
        String type,
        String role,
        String text,
        String toolName,
        String toolInputJson,
        String toolOutputJson,
        Map<String, Object> metadata,
        Instant observedAt) {

    public static BeatEvent from(AgentEvent event) {

        return new BeatEvent(
                event.id(),
                event.sessionId(),
                event.eventType(),
                event.role(),
                event.text(),
                event.toolName(),
                event.toolInputJson(),
                event.toolOutputJson(),
                event.metadata(),
                event.observedAt());
    }
}
