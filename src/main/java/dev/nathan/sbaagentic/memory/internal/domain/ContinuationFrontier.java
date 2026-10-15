package dev.nathan.sbaagentic.memory.internal.domain;

import java.time.Instant;
import java.util.List;

/**
 * The trajectory frontier for one project at one instant. Times are effective times: a capture
 * dated after {@code now} counts as {@code now}. {@code candidates} are ranked but not yet cut.
 */
public record ContinuationFrontier(
        Instant now,
        Instant headAt,
        Instant newestEvidenceAt,
        boolean stale,
        boolean futureDated,
        int captureCount,
        List<ContinuationCapture> frontierHandoffs,
        List<ContinuationCapture> decisions,
        List<ContinuationCandidate> candidates) {

    public ContinuationFrontier {
        frontierHandoffs = List.copyOf(frontierHandoffs);
        decisions = List.copyOf(decisions);
        candidates = List.copyOf(candidates);
    }

    public boolean empty() {

        return captureCount == 0;
    }
}
