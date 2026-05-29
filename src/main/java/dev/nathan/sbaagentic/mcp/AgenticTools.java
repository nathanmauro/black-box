package dev.nathan.sbaagentic.mcp;

import java.util.List;

import dev.nathan.sbaagentic.ai.LocalAiClient;
import dev.nathan.sbaagentic.context.CaptureDecisionRequest;
import dev.nathan.sbaagentic.context.CaptureHandoffRequest;
import dev.nathan.sbaagentic.context.ContextService;
import dev.nathan.sbaagentic.context.RecallResult;
import dev.nathan.sbaagentic.event.EventRepository;
import dev.nathan.sbaagentic.event.IngestResponse;
import dev.nathan.sbaagentic.search.SearchResponse;
import dev.nathan.sbaagentic.search.SearchService;
import dev.nathan.sbaagentic.session.AgentSession;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * The MCP tool surface — the actual nervous system of Black Box. The write tools
 * ({@code captureDecision}, {@code captureHandoff}, {@code captureObservation}) and the read tool
 * ({@code recallContext}) are the loop the read-only sibling tools deliberately cannot offer: an
 * agent commits structured intent and reads another agent's prior reasoning back out, mid-task,
 * entirely on localhost.
 */
@Component
public class AgenticTools {

    private final EventRepository repository;
    private final ContextService contextService;
    private final SearchService searchService;
    private final LocalAiClient localAiClient;

    public AgenticTools(
            EventRepository repository,
            ContextService contextService,
            SearchService searchService,
            LocalAiClient localAiClient) {
        this.repository = repository;
        this.contextService = contextService;
        this.searchService = searchService;
        this.localAiClient = localAiClient;
    }

    @Tool(description = "List recent local agent sessions captured from Claude Code, Codex, or manual CLI input.")
    public List<AgentSession> recentSessions(
            @ToolParam(description = "Maximum number of sessions to return. Use 10 unless the user asks for more.") int limit) {
        return repository.recentSessions(Math.max(1, Math.min(limit, 50)));
    }

    @Tool(description = "Search captured local agent events and sessions by free text.")
    public SearchResponse searchSessions(
            @ToolParam(description = "Search query text.") String query,
            @ToolParam(description = "Maximum number of results to return.") int limit) {
        return searchService.search(query, Math.max(1, Math.min(limit, 50)));
    }

    @Tool(description = "Recall structured prior intent — decisions and handoffs that earlier agents "
            + "(or an earlier you) committed — before starting work, so you do not re-decide what was "
            + "already settled. Returns the decision, its rationale, alternatives weighed, open loops, "
            + "and confidence, not raw text hits.")
    public RecallResult recallContext(
            @ToolParam(description = "Repo path or topic to anchor recall to. Matches the working "
                    + "directory of prior sessions or the captured text. Leave blank for the most "
                    + "recent intent across all repos.") String repoOrTopic,
            @ToolParam(description = "Only recall intent observed within this many hours. Use 168 (one "
                    + "week) unless you need a wider or narrower window.") int withinHours,
            @ToolParam(description = "Which kinds of intent to recall: any of 'decision', 'handoff', "
                    + "'observation'. Leave empty to recall decisions and handoffs.") List<String> kinds) {
        return contextService.recall(repoOrTopic, withinHours, kinds);
    }

    @Tool(description = "Commit a decision you made into the recorder so later agents can recall WHY, "
            + "not just what. Capture the choice, the reasoning, the alternatives you rejected, how "
            + "confident you are, and anything you knowingly left unfinished.")
    public IngestResponse captureDecision(
            @ToolParam(description = "Source client: claude, codex, or manual.") String source,
            @ToolParam(description = "Client session id or stable grouping key for your run.") String clientSessionId,
            @ToolParam(description = "Repo path this decision is about (your working directory). "
                    + "Lets later agents recall it by location.") String repo,
            @ToolParam(description = "The decision you made, in one line.") String decision,
            @ToolParam(description = "Why you chose it.") String rationale,
            @ToolParam(description = "Alternatives you considered and rejected.") List<String> alternatives,
            @ToolParam(description = "How confident you are, 0.0 to 1.0.") double confidence,
            @ToolParam(description = "Open loops: things this decision leaves unfinished or unverified.") List<String> openLoops) {
        return contextService.captureDecision(new CaptureDecisionRequest(
                source, clientSessionId, repo, decision, rationale, alternatives, confidence, openLoops));
    }

    @Tool(description = "Leave a handoff for whoever picks this work up next — another agent, another "
            + "tool, or a future you. The open loops and next action are what a fresh agent recalls to "
            + "continue without losing the thread.")
    public IngestResponse captureHandoff(
            @ToolParam(description = "Source client: claude, codex, or manual.") String source,
            @ToolParam(description = "Client session id or stable grouping key for your run.") String clientSessionId,
            @ToolParam(description = "Repo path this handoff is about (your working directory).") String repo,
            @ToolParam(description = "Who this is for, e.g. 'codex', 'next-session', or a teammate.") String toAgent,
            @ToolParam(description = "What was done and where things stand.") String contextSummary,
            @ToolParam(description = "Open loops still outstanding.") List<String> openLoops,
            @ToolParam(description = "The single most useful next action.") String nextAction) {
        return contextService.captureHandoff(new CaptureHandoffRequest(
                source, clientSessionId, repo, toAgent, contextSummary, openLoops, nextAction));
    }

    @Tool(description = "Capture a free-form observation or note into the local recorder.")
    public IngestResponse captureObservation(
            @ToolParam(description = "Source client: claude, codex, or manual.") String source,
            @ToolParam(description = "Client session id or stable grouping key.") String clientSessionId,
            @ToolParam(description = "Repo path this note is about, if any.") String repo,
            @ToolParam(description = "Text to capture.") String text) {
        return contextService.captureObservation(source, clientSessionId, repo, text);
    }

    @Tool(description = "Check the local AI backend status for LM Studio or another OpenAI-compatible local model server.")
    public Object localModelStatus() {
        return localAiClient.health();
    }
}
