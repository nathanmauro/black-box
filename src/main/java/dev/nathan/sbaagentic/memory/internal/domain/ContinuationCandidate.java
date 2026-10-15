package dev.nathan.sbaagentic.memory.internal.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A ranked, deduplicated next step or open loop. {@code primary} is the strongest source (best tier,
 * then newest, then event ID) and supplies the basis, the collapsed {@code text} and the {@code id};
 * {@code recordedAt} is the newest source's time. {@code sources} are newest first, and
 * {@link #primaryFirst()} is the order in which they are listed.
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

    /** The order sources are listed in: the primary source, then every other source newest first. */
    public List<ContinuationSource> primaryFirst() {
        List<ContinuationSource> ordered = new ArrayList<>(sources.size() + 1);
        ordered.add(primary);
        sources.stream().filter(source -> !source.equals(primary)).forEach(ordered::add);

        return ordered;
    }
}
