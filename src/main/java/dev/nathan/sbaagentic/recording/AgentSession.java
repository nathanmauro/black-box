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
        long eventCount,
        String spawnedBy,
        String firstHumanTurn) {

    /** Convenience for callers that do not know the first human turn ({@code null}). */
    public AgentSession(
            String id,
            String source,
            String clientSessionId,
            String title,
            String cwd,
            String summary,
            Instant startedAt,
            Instant lastSeenAt,
            long eventCount,
            String spawnedBy) {
        this(id, source, clientSessionId, title, cwd, summary, startedAt, lastSeenAt, eventCount, spawnedBy, null);
    }
}
