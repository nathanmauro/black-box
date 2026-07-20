package dev.nathan.sbaagentic.recording;

import java.util.List;
import java.util.Optional;

/** Public recording queries and session mutations used by neighboring modules. */
public interface RecordingCatalog {

    Optional<AgentSession> findSession(String source, String clientSessionId);

    Optional<AgentSession> findSessionById(String id);

    List<AgentSession> recentSessions(int limit);

    List<AgentSession> recentSessionsMissingSummary(int limit);

    List<AgentEvent> eventsForSession(String sessionId, int limit);

    EventFeedResponse feed(
            String query,
            boolean meaningfulOnly,
            String before,
            String since,
            List<String> projectScopes,
            int limit);

    default EventFeedResponse feed(
            String query, boolean meaningfulOnly, String before, String since, int limit) {
        return feed(query, meaningfulOnly, before, since, List.of(), limit);
    }

    void saveSummaryAndTitle(String sessionId, String summary, String title, int titleRank);

    StorageStats stats();

    DashboardStats dashboardStats();
}
