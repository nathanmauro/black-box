package dev.nathan.sbaagentic.memory.internal.adapter.in.mcp;

import java.util.List;
import java.util.function.Supplier;

import dev.nathan.sbaagentic.memory.MemoryRecallOperations;
import dev.nathan.sbaagentic.memory.MemorySearchOperations;
import dev.nathan.sbaagentic.memory.RecallResult;
import dev.nathan.sbaagentic.memory.SearchResponse;
import dev.nathan.sbaagentic.recording.AgentSession;
import dev.nathan.sbaagentic.recording.CaptureDecisionRequest;
import dev.nathan.sbaagentic.recording.CaptureHandoffRequest;
import dev.nathan.sbaagentic.recording.IngestResponse;
import dev.nathan.sbaagentic.recording.RecordingCaptureOperations;
import dev.nathan.sbaagentic.recording.RecordingCatalog;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.stereotype.Component;

/** MCP adapter for recording capture and memory retrieval operations. */
@Component
public class MemoryMcpTools implements Supplier<ToolCallback[]> {

    private final RecordingCatalog recordingCatalog;
    private final MemoryRecallOperations memoryRecall;
    private final MemorySearchOperations memorySearch;
    private final RecordingCaptureOperations captureOperations;

    public MemoryMcpTools(
            RecordingCatalog recordingCatalog,
            MemoryRecallOperations memoryRecall,
            MemorySearchOperations memorySearch,
            RecordingCaptureOperations captureOperations) {
        this.recordingCatalog = recordingCatalog;
        this.memoryRecall = memoryRecall;
        this.memorySearch = memorySearch;
        this.captureOperations = captureOperations;
    }

    @Override
    public ToolCallback[] get() {
        return MethodToolCallbackProvider.builder().toolObjects(this).build().getToolCallbacks();
    }

    @Tool(description = "List recent local agent sessions captured from Claude Code, Codex, or manual CLI input.")
    public List<AgentSession> recentSessions(
            @ToolParam(description = "Maximum number of sessions to return. Use 10 unless the user asks for more.") int limit) {
        return recordingCatalog.recentSessions(Math.max(1, Math.min(limit, 50)));
    }

    @Tool(description = "Search captured local agent events and sessions by free text.")
    public SearchResponse searchSessions(
            @ToolParam(description = "Search query text.") String query,
            @ToolParam(description = "Maximum number of results to return.") int limit) {
        return memorySearch.search(query, Math.max(1, Math.min(limit, 50)));
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
        return memoryRecall.recall(repoOrTopic, withinHours, kinds);
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
        return captureOperations.captureDecision(new CaptureDecisionRequest(
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
        return captureOperations.captureHandoff(new CaptureHandoffRequest(
                source, clientSessionId, repo, toAgent, contextSummary, openLoops, nextAction));
    }

    @Tool(description = "Capture a free-form observation or note into the local recorder.")
    public IngestResponse captureObservation(
            @ToolParam(description = "Source client: claude, codex, or manual.") String source,
            @ToolParam(description = "Client session id or stable grouping key.") String clientSessionId,
            @ToolParam(description = "Repo path this note is about, if any.") String repo,
            @ToolParam(description = "Text to capture.") String text) {
        return captureOperations.captureObservation(source, clientSessionId, repo, text);
    }
}
