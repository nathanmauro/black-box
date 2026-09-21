package dev.nathan.sbaagentic.memory.internal.application.port;

import java.time.Clock;
import java.util.List;
import dev.nathan.sbaagentic.query.EventQuery;

/** Read projections never select arbitrary metadata or tool JSON. */
public interface CompactEventReader {
    List<Candidate> searchCompact(EventQuery query, List<String> projectScopes, int limit,
            Clock clock, String excludeSession);

    record Candidate(String eventId, String sessionId, String clientSessionId, String source,
            String eventType, String role, String observedAt, String text) { }
}
