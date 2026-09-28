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
    default List<AgentSession> recentSessions(int limit, boolean includeChildren) {

        return recentSessions(limit, includeChildren, false);
    }

    /** {@code humanOnly} keeps only sessions that have a classified human turn ({@code firstHumanTurn != null}). */
    List<AgentSession> recentSessions(int limit, boolean includeChildren, boolean humanOnly);

    List<AgentSession> recentSessionsMissingSummary(int limit);

    default List<AgentEvent> eventsForSession(String sessionId, int limit) {

        return eventsForSession(sessionId, limit, false);
    }

    /** {@code humanOnly} keeps only the session's human turns. */
    List<AgentEvent> eventsForSession(String sessionId, int limit, boolean humanOnly);

    /** Cursor-paged event search with a hard internal-session boundary. */
    default EventFeedResponse feedForSession(String sessionId, String query, String before, int limit) {

        return feedForSession(sessionId, query, before, limit, false);
    }

    /** {@code humanOnly} keeps only the session's human turns. */
    EventFeedResponse feedForSession(String sessionId, String query, String before, int limit, boolean humanOnly);

    /** Known transcript paths observed for this session, ordered from strongest to weakest. */
    List<String> transcriptPathsForSession(String sessionId);

    /** Lightweight text-only rows used to deduplicate transcript messages across cursor pages. */
    List<AgentEvent> conversationEventsForSession(String sessionId);

    default EventFeedResponse feed(
            String query, boolean meaningfulOnly, String before, String since, List<String> projectScopes, int limit) {

        return feed(query, meaningfulOnly, before, since, projectScopes, limit, false);
    }

    /** {@code humanOnly} keeps only classified human turns; keyset paging is unaffected. */
    EventFeedResponse feed(
            String query,
            boolean meaningfulOnly,
            String before,
            String since,
            List<String> projectScopes,
            int limit,
            boolean humanOnly);

    default EventFeedResponse feed(String query, boolean meaningfulOnly, String before, String since, int limit) {

        return feed(query, meaningfulOnly, before, since, List.of(), limit);
    }

    default EventFacetCounts facetCounts(String query, boolean meaningfulOnly, List<String> projectScopes) {

        return facetCounts(query, meaningfulOnly, projectScopes, false);
    }

    EventFacetCounts facetCounts(String query, boolean meaningfulOnly, List<String> projectScopes, boolean humanOnly);

    default EventFacetCounts facetCounts(String query, boolean meaningfulOnly) {

        return facetCounts(query, meaningfulOnly, List.of());
    }

    void saveSummaryAndTitle(String sessionId, String summary, String title, int titleRank);

    StorageStats stats();

    DashboardStats dashboardStats();
}
