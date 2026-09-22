package dev.nathan.sbaagentic.memory;

import java.time.Instant;
import java.util.List;

/**
 * A single piece of prior agent intent surfaced by {@link MemoryRecallOperations#recall}.
 *
 * <p>This is the structured projection that makes Black Box more than a search box: instead of
 * returning raw text hits, recall returns the decision, its rationale, the alternatives that were
 * weighed, the open loops left behind, and the confidence — exactly the shape an agent needs to
 * pick up where another agent (or an earlier self) left off. Fields not relevant to a given
 * {@code kind} are {@code null}.
 */
public record RecalledItem(
        String eventId,
        String sessionId,
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
        String toAgent,
        /**
         * True cosine similarity between the recall query embedding and this item's stored
         * embedding. {@code null} means recall had no usable query vector (lexical mode, including
         * blank/path/id scopes, embedder unavailable, or semantic recall failure) or this item has no
         * stored embedding. This field never carries lexical, rank-fusion, or placeholder scores.
         */
        Double score) {}
