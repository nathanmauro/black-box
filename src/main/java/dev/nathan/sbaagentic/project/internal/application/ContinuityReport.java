package dev.nathan.sbaagentic.project.internal.application;

import java.util.List;
import java.util.Map;

/**
 * Continuity lint results. Every string that came from storage has passed through export
 * redaction; {@code detail} and {@code notes} are generated text.
 */
public record ContinuityReport(
        String generatedAt,
        String project,
        int staleAfterHours,
        Scan scan,
        Map<String, Integer> ruleCounts,
        int findingCount,
        boolean findingsTruncated,
        List<Finding> findings,
        List<String> notes) {

    /** What was read; {@code truncated} is true when the store held more rows than one scan reads. */
    public record Scan(
            int decisions,
            int handoffs,
            int observations,
            int braids,
            long capturesInStore,
            long braidsInStore,
            boolean truncated) {}

    public record Finding(
            String rule,
            String severity,
            String subjectType,
            String subjectId,
            String sessionId,
            String project,
            String observedAt,
            String excerpt,
            String detail) {}
}
