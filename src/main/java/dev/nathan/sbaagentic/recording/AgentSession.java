package dev.nathan.sbaagentic.recording;

import java.time.Instant;

public record AgentSession(
        String id,
        String source,
        String clientSessionId,
        String title,
        String cwd,
        String summary,
        Instant startedAt,
        Instant lastSeenAt,
        long eventCount) {
}
