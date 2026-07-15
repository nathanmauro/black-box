package dev.nathan.sbaagentic.project;

import java.time.Instant;
import java.util.List;

public record ProjectSummary(
        String projectKey,
        String canonicalKey,
        String label,
        long sessionCount,
        long eventCount,
        long savedMeldCount,
        Instant firstSeenAt,
        Instant lastSeenAt,
        List<ProjectScope> scopes) {
}
