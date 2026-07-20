package dev.nathan.sbaagentic.mcp;

import java.util.List;
import java.util.Map;

import dev.nathan.sbaagentic.ai.LocalAiClient;
import dev.nathan.sbaagentic.context.ContextService;
import dev.nathan.sbaagentic.context.RecallResult;
import dev.nathan.sbaagentic.event.EventRepository;
import dev.nathan.sbaagentic.event.IngestResponse;
import dev.nathan.sbaagentic.project.ProjectSummaryTools;
import dev.nathan.sbaagentic.recording.RecordingMemoryTools;
import dev.nathan.sbaagentic.search.SearchResponse;
import dev.nathan.sbaagentic.search.SearchService;
import dev.nathan.sbaagentic.session.AgentSession;
import dev.nathan.sbaagentic.task.TaskChange;
import dev.nathan.sbaagentic.task.TaskSnapshot;
import dev.nathan.sbaagentic.task.TaskSpec;
import dev.nathan.sbaagentic.workflow.WorkflowTools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
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

    private final RecordingMemoryTools recordingMemoryTools;
    private final ProjectSummaryTools projectSummaryTools;
    private final WorkflowTools workflowTools;

    @Autowired
    public AgenticTools(
            RecordingMemoryTools recordingMemoryTools,
            ProjectSummaryTools projectSummaryTools,
            WorkflowTools workflowTools) {
        this.recordingMemoryTools = recordingMemoryTools;
        this.projectSummaryTools = projectSummaryTools;
        this.workflowTools = workflowTools;
    }

    /** Preserves source compatibility for callers that only use the original seven tools. */
    public AgenticTools(
            EventRepository repository,
            ContextService contextService,
            SearchService searchService,
            LocalAiClient localAiClient) {
        this(
                new RecordingMemoryTools(repository, contextService, searchService),
                new ProjectSummaryTools(localAiClient),
                null);
    }

    @Tool(description = "List recent local agent sessions captured from Claude Code, Codex, or manual CLI input.")
    public List<AgentSession> recentSessions(
            @ToolParam(description = "Maximum number of sessions to return. Use 10 unless the user asks for more.") int limit) {
        return recordingMemoryTools.recentSessions(limit);
    }

    @Tool(description = "Search captured local agent events and sessions by free text.")
    public SearchResponse searchSessions(
            @ToolParam(description = "Search query text.") String query,
            @ToolParam(description = "Maximum number of results to return.") int limit) {
        return recordingMemoryTools.searchSessions(query, limit);
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
        return recordingMemoryTools.recallContext(repoOrTopic, withinHours, kinds);
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
        return recordingMemoryTools.captureDecision(
                source, clientSessionId, repo, decision, rationale, alternatives, confidence, openLoops);
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
        return recordingMemoryTools.captureHandoff(
                source, clientSessionId, repo, toAgent, contextSummary, openLoops, nextAction);
    }

    @Tool(description = "Capture a free-form observation or note into the local recorder.")
    public IngestResponse captureObservation(
            @ToolParam(description = "Source client: claude, codex, or manual.") String source,
            @ToolParam(description = "Client session id or stable grouping key.") String clientSessionId,
            @ToolParam(description = "Repo path this note is about, if any.") String repo,
            @ToolParam(description = "Text to capture.") String text) {
        return recordingMemoryTools.captureObservation(source, clientSessionId, repo, text);
    }

    @Tool(description = "Check the local AI backend status for LM Studio or another OpenAI-compatible local model server.")
    public Object localModelStatus() {
        return projectSummaryTools.localModelStatus();
    }

    @Tool(
            description = "Create a durable project-scoped spec whose frozen body is returned with every claimed task.",
            resultConverter = RestJsonToolCallResultConverter.class)
    public TaskSpec createSpec(
            @ToolParam(description = "Project key used to group and filter the spec's tasks.") String projectKey,
            @ToolParam(description = "Human-readable spec title.") String title,
            @ToolParam(description = "Canonical frozen spec body; agents receive this without resolving files.") String body,
            @ToolParam(required = false,
                    description = "Optional provenance object such as repo, path, and sha.") Map<String, Object> specRef,
            @ToolParam(description = "Agent or source creating the spec.") String actor) {
        return workflowTools.createSpec(projectKey, title, body, specRef, actor);
    }

    @Tool(
            description = "Enqueue an open task under an existing spec in one required routing lane.",
            resultConverter = RestJsonToolCallResultConverter.class)
    public TaskChange enqueueTask(
            @ToolParam(description = "UUID of the frozen spec this task belongs to.") String specId,
            @ToolParam(description = "Human-readable task title.") String title,
            @ToolParam(description = "Required exact routing lane, for example codex or claude.") String lane,
            @ToolParam(description = "Higher values are claimed first; equal values are FIFO.") int priority,
            @ToolParam(description = "Agent or source enqueuing the task.") String actor) {
        return workflowTools.enqueueTask(specId, title, lane, priority, actor);
    }

    @Tool(
            description = "Atomically claim the highest-priority oldest open task in an exact lane. Returns empty when none exists.",
            resultConverter = RestJsonToolCallResultConverter.class)
    public TaskChange claimNextTask(
            @ToolParam(description = "Required exact lane to claim from.") String lane,
            @ToolParam(description = "Agent identity that will own the claimed task.") String agent) {
        return workflowTools.claimNextTask(lane, agent);
    }

    @Tool(
            description = "Apply an allowed task lifecycle update: block, reset to open, or cancel.",
            resultConverter = RestJsonToolCallResultConverter.class)
    public TaskChange updateTaskStatus(
            @ToolParam(description = "UUID of the task to update.") String taskId,
            @ToolParam(description = "Agent or operator causing the transition.") String actor,
            @ToolParam(description = "Target status: blocked, open (reset), or cancelled.") String status,
            @ToolParam(required = false,
                    description = "Required nonblank reason when target status is blocked.") String blockedReason) {
        return workflowTools.updateTaskStatus(taskId, actor, status, blockedReason);
    }

    @Tool(
            description = "Complete an owned in-progress task, capture a recallable Handoff, and link its event id.",
            resultConverter = RestJsonToolCallResultConverter.class)
    public TaskChange completeTask(
            @ToolParam(description = "UUID of the task to complete.") String taskId,
            @ToolParam(description = "Current claimant completing the task.") String actor,
            @ToolParam(description = "Source client for the completion Handoff, for example codex.") String source,
            @ToolParam(description = "Real source client session id for the completion Handoff.") String clientSessionId,
            @ToolParam(description = "What was completed and where the work stands.") String summary,
            @ToolParam(required = false, description = "Optional remaining open loops.") List<String> openLoops,
            @ToolParam(description = "Required next action for the receiving agent.") String nextAction) {
        return workflowTools.completeTask(
                taskId, actor, source, clientSessionId, summary, openLoops, nextAction);
    }

    @Tool(
            description = "List task snapshots with optional project, lane, and status filters and a bounded limit.",
            resultConverter = RestJsonToolCallResultConverter.class)
    public List<TaskSnapshot> listTasks(
            @ToolParam(required = false,
                    description = "Optional exact project key; blank means all projects.") String projectKey,
            @ToolParam(required = false,
                    description = "Optional exact lane; blank means all lanes.") String lane,
            @ToolParam(required = false,
                    description = "Optional status: open, in_progress, blocked, done, or cancelled.") String status,
            @ToolParam(required = false,
                    description = "Maximum snapshots to return, defaulting to 100 and clamped to 1 through 250.")
                    Integer limit) {
        return workflowTools.listTasks(projectKey, lane, status, limit);
    }

    @Tool(
            description = "Get a durable spec, including its full frozen body and optional provenance.",
            resultConverter = RestJsonToolCallResultConverter.class)
    public TaskSpec getSpec(
            @ToolParam(description = "UUID of the spec to retrieve.") String specId) {
        return workflowTools.getSpec(specId);
    }
}
