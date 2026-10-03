package dev.nathan.sbaagentic.memory.internal.adapter.in.mcp;

import dev.nathan.sbaagentic.memory.MemoryRecallOperations;
import dev.nathan.sbaagentic.memory.MemorySearchOperations;
import dev.nathan.sbaagentic.memory.RecallRequestContext;
import dev.nathan.sbaagentic.memory.RecallResult;
import dev.nathan.sbaagentic.memory.SearchResponse;
import dev.nathan.sbaagentic.recording.AgentSession;
import dev.nathan.sbaagentic.recording.CaptureDecisionRequest;
import dev.nathan.sbaagentic.recording.CaptureHandoffRequest;
import dev.nathan.sbaagentic.recording.CaptureIdeaRequest;
import dev.nathan.sbaagentic.recording.CaptureProjectionRequest;
import dev.nathan.sbaagentic.recording.IngestResponse;
import dev.nathan.sbaagentic.recording.ProjectionPath;
import dev.nathan.sbaagentic.recording.RecordingCaptureOperations;
import dev.nathan.sbaagentic.recording.RecordingCatalog;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.mcp.McpToolUtils;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
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

        return Arrays.stream(MethodToolCallbackProvider.builder()
                        .toolObjects(this)
                        .build()
                        .getToolCallbacks())
                .map(callback -> callback.getToolDefinition().name().equals("recallContext")
                        ? withOptionalToolContext(callback)
                        : callback)
                .toArray(ToolCallback[]::new);
    }

    private static ToolCallback withOptionalToolContext(ToolCallback delegate) {

        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {

                return delegate.getToolDefinition();
            }

            @Override
            public ToolMetadata getToolMetadata() {

                return delegate.getToolMetadata();
            }

            @Override
            public String call(String input) {

                return call(input, null);
            }

            @Override
            public String call(String input, ToolContext context) {

                // Spring requires a nonempty context for ToolContext parameters, even without an exchange.
                return delegate.call(
                        input,
                        context == null || context.getContext().isEmpty()
                                ? new ToolContext(Map.of("blackboxRecall", true))
                                : context);
            }
        };
    }

    private static int clampLimit(Integer limit) {

        return limit == null ? 10 : Math.max(1, Math.min(limit, 50));
    }

    private static int clampMaxChars(Integer maxChars) {

        return RecallResultClamp.normalizeMaxChars(maxChars);
    }

    @Tool(
            description = "List recent local agent sessions captured from Claude Code, Codex, or manual CLI input. "
                    + "Each session carries firstHumanTurn: the first thing the human actually said "
                    + "(harness boilerplate removed; null when the session has no classified human turn). "
                    + "Classification is heuristic.")
    public List<AgentSession> recentSessions(
            @ToolParam(required = false, description = "Maximum number of sessions to return. Omit for 10.")
                    Integer limit,
            @ToolParam(
                            required = false,
                            description =
                                    "true = only sessions that contain a human turn (firstHumanTurn is set). Omit or false for all sessions.")
                    Boolean humanOnly) {

        return recordingCatalog.recentSessions(clampLimit(limit), false, Boolean.TRUE.equals(humanOnly));
    }

    @Tool(
            description =
                    "Raw diagnostic search of captured events. Results include full tool/metadata payloads; limit bounds rows only. Prefer searchContext for bounded discovery, recallContext for structured intent. Events carry humanText: the human's words for classified human turns, else null.")
    public SearchResponse searchSessions(
            @ToolParam(description = "Search query text.") String query,
            @ToolParam(required = false, description = "Maximum number of results to return. Omit for 10.")
                    Integer limit,
            @ToolParam(
                            required = false,
                            description =
                                    "true = match only the human's own turns, with harness boilerplate removed (local index only, no Elasticsearch). Omit or false to search everything.")
                    Boolean humanOnly) {

        return memorySearch.search(query, clampLimit(limit), Boolean.TRUE.equals(humanOnly));
    }

    @Tool(
            description = "Recall structured prior intent — decisions, handoffs, observations, projections, "
                    + "and ideas "
                    + "that earlier agents "
                    + "(or an earlier you) committed — before starting work, so you do not re-decide what was "
                    + "already settled. Returns structured fields and the full captured text, not raw search hits.")
    public RecallResult recallContext(
            @ToolParam(
                            required = false,
                            description =
                                    "Legacy repo path, repo name, event id, or topic. Do not combine with project/query. Matching ids, "
                                            + "working directories, repo metadata, or captured text anchor both lexical "
                                            + "and semantic candidates; otherwise this is a semantic topic query. Leave "
                                            + "blank for the most recent intent across all repos.")
                    String repoOrTopic,
            @ToolParam(
                            required = false,
                            description =
                                    "Only recall intent observed within this many hours. Omit for 168 " + "(one week).")
                    Integer withinHours,
            @ToolParam(
                            required = false,
                            description = "Which kinds of intent to recall: any of 'decision', 'handoff', "
                                    + "'observation', 'projection', or 'idea'. Omit to recall decisions and handoffs.")
                    List<String> kinds,
            @ToolParam(
                            required = false,
                            description = "Maximum number of items to return. Omit for 10, max 50. Recalled "
                                    + "items carry full captured text, so raise this deliberately.")
                    Integer limit,
            @ToolParam(
                            required = false,
                            description = "Upper bound on the total characters of the returned items' text fields. "
                                    + "Omit for 24000 (minimum 500). When the result overflows, the first overflowing "
                                    + "item's Observation/Projection body, then rationale, then headline, is cut with a visible '… (+N chars)' suffix and "
                                    + "every later item is dropped; `truncated` reports whether anything was cut.")
                    Integer maxChars,
            @ToolParam(
                            required = false,
                            description = "Optional telemetry declaration: codex, claude, manual, or other. "
                                    + "Not an authenticated identity; omit for unknown.")
                    String telemetryClient,
            @ToolParam(
                            required = false,
                            description = "Optional telemetry purpose: normal, audit, or test. "
                                    + "Use audit for research probes; omit for unknown.")
                    String telemetryPurpose,
            @ToolParam(
                            required = false,
                            description = "Optional operator-configured safe project alias. "
                                    + "Never supply a private path; unconfigured aliases are discarded.")
                    String telemetryProject,
            @ToolParam(
                            required = false,
                            description =
                                    "Exact canonical project path (registered aliases included). Use with query; omit repoOrTopic.")
                    String project,
            @ToolParam(
                            required = false,
                            description =
                                    "Question or topic to recall. Only this text is embedded; blank returns recent intent within project.")
                    String query,
            @ToolParam(
                            required = false,
                            description =
                                    "Include explicitly replaced Decisions for historical inspection. Defaults to false.")
                    Boolean includeSuperseded,
            ToolContext toolContext) {
        try (var ignored = RecallRequestContext.open(
                "mcp", recallClient(toolContext, telemetryClient), telemetryPurpose, telemetryProject)) {
            RecallResult result = project == null && query == null && !Boolean.TRUE.equals(includeSuperseded)
                    ? memoryRecall.recall(repoOrTopic, withinHours == null ? 0 : withinHours, kinds, limit)
                    : memoryRecall.recall(
                            repoOrTopic,
                            project,
                            query,
                            withinHours == null ? 0 : withinHours,
                            kinds,
                            limit,
                            Boolean.TRUE.equals(includeSuperseded));

            return RecallResultClamp.clamp(result, clampMaxChars(maxChars));
        }
    }

    public RecallResult recallContext(
            String repoOrTopic,
            Integer withinHours,
            List<String> kinds,
            Integer limit,
            Integer maxChars,
            String telemetryClient,
            String telemetryPurpose,
            String telemetryProject,
            ToolContext toolContext) {

        return recallContext(
                repoOrTopic,
                withinHours,
                kinds,
                limit,
                maxChars,
                telemetryClient,
                telemetryPurpose,
                telemetryProject,
                null,
                null,
                null,
                toolContext);
    }

    public RecallResult recallContext(
            String repoOrTopic, Integer withinHours, List<String> kinds, Integer limit, Integer maxChars) {

        return recallContext(repoOrTopic, withinHours, kinds, limit, maxChars, null, null, null, null);
    }

    private static String recallClient(ToolContext context, String declaredClient) {
        try {
            String name = McpToolUtils.getMcpExchange(context)
                    .map(exchange -> exchange.getClientInfo())
                    .map(info -> info.name())
                    .orElse("");
            if (name.length() <= 128) {
                String normalized = name.strip().toLowerCase(Locale.ROOT);
                if (normalized.matches("codex(?:[-_ /].*)?"))

                    return "codex";

                if (normalized.matches("claude(?:[-_ /].*)?"))

                    return "claude";
            }
        } catch (RuntimeException ignored) {
            // Missing/malformed attribution must never fail the tool.
        }

        return declaredClient;
    }

    @Tool(
            description = "Commit a decision you made into the recorder so later agents can recall WHY, "
                    + "not just what. Capture the choice, the reasoning, the alternatives you rejected, how "
                    + "confident you are, and anything you knowingly left unfinished.")
    public IngestResponse captureDecision(
            @ToolParam(description = "Source client: claude, codex, or manual.") String source,
            @ToolParam(description = "Client session id or stable grouping key for your run.") String clientSessionId,
            @ToolParam(
                            description = "Repo path this decision is about (your working directory). "
                                    + "Lets later agents recall it by location.")
                    String repo,
            @ToolParam(description = "The decision you made, in one line.") String decision,
            @ToolParam(description = "Why you chose it.") String rationale,
            @ToolParam(description = "Alternatives you considered and rejected.") List<String> alternatives,
            @ToolParam(required = false, description = "How confident you are, 0.0 to 1.0. Omit if unsure.")
                    Double confidence,
            @ToolParam(description = "Open loops: things this decision leaves unfinished or unverified.")
                    List<String> openLoops,
            @ToolParam(
                            required = false,
                            description =
                                    "Event ID of one current Decision this replaces in the same logical project. Requires a nonblank rationale; preserves original evidence.")
                    String supersedes) {

        return captureOperations.captureDecision(new CaptureDecisionRequest(
                source, clientSessionId, repo, decision, rationale, alternatives, confidence, openLoops, supersedes));
    }

    public IngestResponse captureDecision(
            String source,
            String clientSessionId,
            String repo,
            String decision,
            String rationale,
            List<String> alternatives,
            Double confidence,
            List<String> openLoops) {

        return captureDecision(
                source, clientSessionId, repo, decision, rationale, alternatives, confidence, openLoops, null);
    }

    @Tool(
            description = "Leave a handoff for whoever picks this work up next — another agent, another "
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

    @Tool(
            description = "Before closing a session, project one to five plausible futures for this repo — "
                    + "where the work could go next. Each path: short title, one-line description, "
                    + "confidence 0..1. Each new capture replaces the last set on the project's trajectory graph.")
    public IngestResponse captureProjection(
            @ToolParam(description = "Source client: claude, codex, or manual.") String source,
            @ToolParam(description = "Client session id or stable grouping key for your run.") String clientSessionId,
            @ToolParam(description = "Repo path this projection is about (your working directory).") String repo,
            @ToolParam(
                            required = false,
                            description = "Why these futures are plausible, or what current work they are based on.")
                    String basis,
            @ToolParam(
                            description = "One to five projected futures. Each item requires a title; "
                                    + "description and confidence 0..1 are optional.")
                    List<ProjectionPath> paths) {

        return captureOperations.captureProjection(
                new CaptureProjectionRequest(source, clientSessionId, repo, basis, paths));
    }

    @Tool(
            description = "Capture an idea someone proposed that is not being acted on right now — a human's "
                    + "aside or an agent's suggestion — so it can be listed, recalled, and resumed later. "
                    + "Ideas are append-only: to change an idea's status, capture it again with the same ideaKey.")
    public IngestResponse captureIdea(
            @ToolParam(description = "Source client: claude, codex, or manual.") String source,
            @ToolParam(description = "Client session id or stable grouping key for your run.") String clientSessionId,
            @ToolParam(
                            required = false,
                            description = "Repo path this idea is about (your working directory), if any. "
                                    + "Used for project scoping and the default ideaKey.")
                    String repo,
            @ToolParam(description = "Short name of the idea.") String title,
            @ToolParam(description = "One sentence: what the idea is.") String oneLiner,
            @ToolParam(
                            description = "Who had the idea: 'human-aside' (the human said it in passing), "
                                    + "'agent-proposed' (an agent suggested it), or 'joint'.")
                    String origin,
            @ToolParam(required = false, description = "Verbatim words from the person or agent who had the idea.")
                    String quote,
            @ToolParam(required = false, description = "Where the idea came from: a session id, a path:line, or a URL.")
                    String sourceRef,
            @ToolParam(required = false, description = "How much the idea has going for it, 0 to 10.") Integer legs,
            @ToolParam(
                            required = false,
                            description = "One of 'untouched' (default), 'partially-built', 'built-unused', "
                                    + "'superseded', or 'tracked'.")
                    String status,
            @ToolParam(required = false, description = "Related threads, ideas, or issue ids.") List<String> connects,
            @ToolParam(required = false, description = "The smallest useful next step if someone picks it up.")
                    String resumeStep,
            @ToolParam(required = false, description = "Optional Linear, Obsidian (obsidian://…), or web link.")
                    String link,
            @ToolParam(required = false, description = "Optional free-form markdown body: prior art, motivating case.")
                    String notes,
            @ToolParam(
                            required = false,
                            description = "Stable identity across status changes. Omit to default to a slug of "
                                    + "the repo name plus the title; reuse it to record a new status.")
                    String ideaKey) {

        return captureOperations.captureIdea(new CaptureIdeaRequest(
                source,
                clientSessionId,
                repo,
                title,
                oneLiner,
                origin,
                quote,
                sourceRef,
                legs,
                status,
                connects,
                resumeStep,
                link,
                notes,
                ideaKey));
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
