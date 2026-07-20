package dev.nathan.sbaagentic.recording;

import java.util.List;

import dev.nathan.sbaagentic.context.CaptureDecisionRequest;
import dev.nathan.sbaagentic.context.CaptureHandoffRequest;
import dev.nathan.sbaagentic.context.ContextService;
import dev.nathan.sbaagentic.context.RecallResult;
import dev.nathan.sbaagentic.event.EventRepository;
import dev.nathan.sbaagentic.event.IngestResponse;
import dev.nathan.sbaagentic.search.SearchResponse;
import dev.nathan.sbaagentic.search.SearchService;
import dev.nathan.sbaagentic.session.AgentSession;

import org.springframework.stereotype.Component;

/**
 * Transitional feature implementation for recording and memory MCP operations.
 *
 * <p>The annotated compatibility surface remains in {@code AgenticTools} until module APIs replace
 * the legacy services. Keeping annotations out of this delegate prevents duplicate MCP tool
 * registration during that transition.</p>
 */
@Component
public class RecordingMemoryTools {

    private final EventRepository repository;
    private final ContextService contextService;
    private final SearchService searchService;

    public RecordingMemoryTools(
            EventRepository repository,
            ContextService contextService,
            SearchService searchService) {
        this.repository = repository;
        this.contextService = contextService;
        this.searchService = searchService;
    }

    public List<AgentSession> recentSessions(int limit) {
        return repository.recentSessions(Math.max(1, Math.min(limit, 50)));
    }

    public SearchResponse searchSessions(String query, int limit) {
        return searchService.search(query, Math.max(1, Math.min(limit, 50)));
    }

    public RecallResult recallContext(String repoOrTopic, int withinHours, List<String> kinds) {
        return contextService.recall(repoOrTopic, withinHours, kinds);
    }

    public IngestResponse captureDecision(
            String source,
            String clientSessionId,
            String repo,
            String decision,
            String rationale,
            List<String> alternatives,
            double confidence,
            List<String> openLoops) {
        return contextService.captureDecision(new CaptureDecisionRequest(
                source, clientSessionId, repo, decision, rationale, alternatives, confidence, openLoops));
    }

    public IngestResponse captureHandoff(
            String source,
            String clientSessionId,
            String repo,
            String toAgent,
            String contextSummary,
            List<String> openLoops,
            String nextAction) {
        return contextService.captureHandoff(new CaptureHandoffRequest(
                source, clientSessionId, repo, toAgent, contextSummary, openLoops, nextAction));
    }

    public IngestResponse captureObservation(
            String source,
            String clientSessionId,
            String repo,
            String text) {
        return contextService.captureObservation(source, clientSessionId, repo, text);
    }
}
