package dev.nathan.sbaagentic.memory.internal.domain;

import java.time.Instant;
import java.util.List;

/**
 * A ranked, deduplicated next step or open loop. {@code text} is the newest source's collapsed text;
 * {@code primary} is the best-tier source, which supplies the basis. Sources are newest first.
 */
public record ContinuationCandidate(
        String id,
        int rank,
        ContinuationBasis basis,
        String text,
        Instant recordedAt,
        int ageDays,
        boolean stale,
        ContinuationSource primary,
        List<ContinuationSource> sources) {

    public ContinuationCandidate {
        sources = List.copyOf(sources);
    }

    public boolean futureDated() {

        return sources.stream().anyMatch(ContinuationSource::futureDated);
    }
}
