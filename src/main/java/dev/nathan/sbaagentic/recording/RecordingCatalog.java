package dev.nathan.sbaagentic.recording;

import java.util.List;
import java.util.Optional;

/** Public recording queries and session mutations used by neighboring modules. */
public interface RecordingCatalog {

    Optional<AgentSession> findSession(String source, String clientSessionId);

    Optional<AgentSession> findSessionById(String id);

    Optional<AgentEvent> findEventById(String id);

    List<AgentSession> recentSessions(int limit);

    /** Flat listing that also includes subagent children; {@link #recentSessions(int)} hides them. */
    List<AgentSession> recentSessions(int limit, boolean includeChildren);

    List<AgentSession> recentSessionsMissingSummary(int limit);

    List<AgentEvent> eventsForSession(String sessionId, int limit);

    /** Cursor-paged event search with a hard internal-session boundary. */
    EventFeedResponse feedForSession(String sessionId, String query, String before, int limit);

    /** Known transcript paths observed for this session, ordered from strongest to weakest. */
    List<String> transcriptPathsForSession(String sessionId);

    /** Lightweight text-only rows used to deduplicate transcript messages across cursor pages. */
    List<AgentEvent> conversationEventsForSession(String sessionId);

    EventFeedResponse feed(
            String query, boolean meaningfulOnly, String before, String since, List<String> projectScopes, int limit);

    default EventFeedResponse feed(String query, boolean meaningfulOnly, String before, String since, int limit) {

        return feed(query, meaningfulOnly, before, since, List.of(), limit);
    }

    EventFacetCounts facetCounts(String query, boolean meaningfulOnly, List<String> projectScopes);

    default EventFacetCounts facetCounts(String query, boolean meaningfulOnly) {

        return facetCounts(query, meaningfulOnly, List.of());
    }

    void saveSummaryAndTitle(String sessionId, String summary, String title, int titleRank);

    StorageStats stats();

    DashboardStats dashboardStats();
}
