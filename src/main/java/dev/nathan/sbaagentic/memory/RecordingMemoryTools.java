package dev.nathan.sbaagentic.memory;

import java.util.List;

import dev.nathan.sbaagentic.recording.CaptureDecisionRequest;
import dev.nathan.sbaagentic.recording.CaptureHandoffRequest;
import dev.nathan.sbaagentic.recording.RecordingCatalog;
import dev.nathan.sbaagentic.recording.IngestResponse;
import dev.nathan.sbaagentic.recording.AgentSession;
import dev.nathan.sbaagentic.recording.RecordingCaptureOperations;

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

    private final RecordingCatalog repository;
    private final MemoryRecallOperations contextService;
    private final MemorySearchOperations searchService;
    private final RecordingCaptureOperations captureOperations;

    public RecordingMemoryTools(
            RecordingCatalog repository,
            MemoryRecallOperations contextService,
            MemorySearchOperations searchService,
            RecordingCaptureOperations captureOperations) {
        this.repository = repository;
        this.contextService = contextService;
        this.searchService = searchService;
        this.captureOperations = captureOperations;
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
        return captureOperations.captureDecision(new CaptureDecisionRequest(
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
        return captureOperations.captureHandoff(new CaptureHandoffRequest(
                source, clientSessionId, repo, toAgent, contextSummary, openLoops, nextAction));
    }

    public IngestResponse captureObservation(
            String source,
            String clientSessionId,
            String repo,
            String text) {
        return captureOperations.captureObservation(source, clientSessionId, repo, text);
    }
}
