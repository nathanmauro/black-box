package dev.nathan.sbaagentic.project;

import java.time.Instant;

public record ProjectSummary(
        String projectKey,
        String canonicalKey,
        String label,
        long sessionCount,
        long eventCount,
        long savedMeldCount,
        Instant firstSeenAt,
        Instant lastSeenAt) {
}
