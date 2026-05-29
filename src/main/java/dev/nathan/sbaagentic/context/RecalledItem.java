package dev.nathan.sbaagentic.context;

import java.time.Instant;
import java.util.List;

/**
 * A single piece of prior agent intent surfaced by {@link ContextService#recall}.
 *
 * <p>This is the structured projection that makes Black Box more than a search box: instead of
 * returning raw text hits, recall returns the decision, its rationale, the alternatives that were
 * weighed, the open loops left behind, and the confidence — exactly the shape an agent needs to
 * pick up where another agent (or an earlier self) left off. Fields not relevant to a given
 * {@code kind} are {@code null}.
 */
public record RecalledItem(
        String eventId,
        String kind,
        String source,
        String clientSessionId,
        String repo,
        Instant observedAt,
        String headline,
        String rationale,
        List<String> alternatives,
        Double confidence,
        List<String> openLoops,
        String nextAction,
        String toAgent) {
}
