package dev.nathan.sbaagentic.memory.internal.application.port;

import dev.nathan.sbaagentic.query.EventQuery;
import java.time.Clock;
import java.util.List;

/** Read projections never select arbitrary metadata or tool JSON. */
public interface CompactEventReader {
    List<Candidate> searchCompact(
            EventQuery query, List<String> projectScopes, int limit, Clock clock, String excludeSession);

    /** Literal keyset page ordered by observed-time key then event ID, both descending. */
    List<PageRow> pageCompact(PageQuery query, int limit);

    record Candidate(
            String eventId,
            String sessionId,
            String clientSessionId,
            String source,
            String eventType,
            String role,
            String observedAt,
            String text) {}

    /**
     * Terms are case-sensitive literal substrings ANDed over text, tool name and stored metadata JSON.
     * Keys are {@code SqlInstant} keys; {@code beforeKey}/{@code beforeId} are both null on a first page.
     */
    record PageQuery(
            List<String> terms,
            String projectExact,
            String sessionId,
            String untilKey,
            String beforeKey,
            String beforeId) {}

    record PageRow(Candidate candidate, String orderKey, String eventId) {}
}
