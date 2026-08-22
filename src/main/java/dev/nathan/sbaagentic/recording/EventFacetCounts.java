package dev.nathan.sbaagentic.recording;

import java.util.List;

/**
 * Query-scoped facet counts for the Stream's counted instrument (spec §6.5, D10). {@code total}
 * counts every event matching the full query; each field list answers "what if I switched this
 * facet" — its own include list is dropped from the WHERE, everything else (negations, other
 * facets, session, time bounds, free text, the meaningful default with its {@code is:all}
 * precedence) applies — so currently-excluded values still show their would-be counts. Lists are
 * capped at the top {@link #VALUE_LIMIT} values by count; the cap bounds the on-demand browser,
 * it is not a claim of completeness.
 *
 * <p>When counts are unavailable — free text present while the FTS backfill is still running, or
 * a live MATCH failure — the envelope degrades to {@code {total: null, fields: null, reason:
 * "backfill"}} instead of running four unindexed LIKE scans. Consumers must omit the number
 * entirely in that case (never render a stale or approximate count).
 */
public record EventFacetCounts(Long total, Fields fields, String reason) {

    public static final int VALUE_LIMIT = 25;

    public record Fields(
            List<ValueCount> source,
            List<ValueCount> kind,
            List<ValueCount> tool,
            List<ValueCount> project) {
    }

    public record ValueCount(String value, long count) {
    }

    public static EventFacetCounts skipped(String reason) {
        return new EventFacetCounts(null, null, reason);
    }
}
