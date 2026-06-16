package dev.nathan.sbaagentic.project;

import java.time.Instant;

public record ProjectMeldSessionRef(
        String id,
        String source,
        String clientSessionId,
        String title,
        String cwd,
        long eventCount,
        Instant startedAt,
        Instant lastSeenAt) {
}
