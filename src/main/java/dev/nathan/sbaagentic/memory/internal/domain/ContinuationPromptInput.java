package dev.nathan.sbaagentic.memory.internal.domain;

import java.time.Instant;
import java.util.List;

/**
 * Everything one prompt may quote. {@code state} is the handoff whose summary becomes Recorded
 * state, or null; {@code sources} are the linked sources, already capped, with {@code omittedSources}
 * counting the rest.
 */
public record ContinuationPromptInput(
        ContinuationCandidate candidate,
        Instant now,
        boolean projectStale,
        Instant headAt,
        ContinuationCapture state,
        boolean handoffsRecorded,
        List<ContinuationCapture> decisions,
        List<ContinuationCandidate> otherLoops,
        List<ContinuationRelation> relations,
        List<ContinuationLink> sources,
        int omittedSources) {

    public ContinuationPromptInput {
        decisions = List.copyOf(decisions);
        otherLoops = List.copyOf(otherLoops);
        relations = List.copyOf(relations);
        sources = List.copyOf(sources);
    }
}
