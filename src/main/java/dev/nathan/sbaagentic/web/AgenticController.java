package dev.nathan.sbaagentic.web;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.nathan.sbaagentic.ask.AskRequest;
import dev.nathan.sbaagentic.ask.AskResponse;
import dev.nathan.sbaagentic.ask.AskRetrieveResponse;
import dev.nathan.sbaagentic.ask.AskService;
import dev.nathan.sbaagentic.ask.AskStatus;
import dev.nathan.sbaagentic.ai.LocalAiClient;
import dev.nathan.sbaagentic.ai.SessionSummaryService;
import dev.nathan.sbaagentic.context.CaptureDecisionRequest;
import dev.nathan.sbaagentic.context.CaptureHandoffRequest;
import dev.nathan.sbaagentic.context.ContextService;
import dev.nathan.sbaagentic.context.RecallResult;
import dev.nathan.sbaagentic.event.AgentEvent;
import dev.nathan.sbaagentic.event.DashboardStats;
import dev.nathan.sbaagentic.event.EventFeedResponse;
import dev.nathan.sbaagentic.event.EventIngestRequest;
import dev.nathan.sbaagentic.event.EventIngestService;
import dev.nathan.sbaagentic.event.EventRepository;
import dev.nathan.sbaagentic.event.IngestResponse;
import dev.nathan.sbaagentic.exporting.SummaryExportService;
import dev.nathan.sbaagentic.project.ProjectService;
import dev.nathan.sbaagentic.project.ProjectMeldPreviewRequest;
import dev.nathan.sbaagentic.project.ProjectMeldPreviewResponse;
import dev.nathan.sbaagentic.project.ProjectMeldSaveRequest;
import dev.nathan.sbaagentic.project.ProjectMeldService;
import dev.nathan.sbaagentic.project.ProjectSavedMeld;
import dev.nathan.sbaagentic.project.ProjectSummary;
import dev.nathan.sbaagentic.project.ProjectTimelineResponse;
import dev.nathan.sbaagentic.search.ElasticIndexClient;
import dev.nathan.sbaagentic.search.SearchResponse;
import dev.nathan.sbaagentic.search.SearchService;
import dev.nathan.sbaagentic.session.AgentSession;
import dev.nathan.sbaagentic.task.ClaimTaskRequest;
import dev.nathan.sbaagentic.task.CompleteTaskRequest;
import dev.nathan.sbaagentic.task.CreateSpecRequest;
import dev.nathan.sbaagentic.task.EnqueueTaskRequest;
import dev.nathan.sbaagentic.task.TaskChange;
import dev.nathan.sbaagentic.task.TaskDomainException;
import dev.nathan.sbaagentic.task.TaskErrorCode;
import dev.nathan.sbaagentic.task.TaskQuery;
import dev.nathan.sbaagentic.task.TaskService;
import dev.nathan.sbaagentic.task.TaskSnapshot;
import dev.nathan.sbaagentic.task.TaskSpec;
import dev.nathan.sbaagentic.task.TaskStatus;
import dev.nathan.sbaagentic.task.UpdateTaskStatusRequest;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class AgenticController {

    private final EventIngestService ingestService;
    private final ContextService contextService;
    private final EventRepository repository;
    private final SearchService searchService;
    private final SessionSummaryService summaryService;
    private final SummaryExportService summaryExportService;
    private final ProjectService projectService;
    private final ProjectMeldService projectMeldService;
    private final LocalAiClient localAiClient;
    private final ElasticIndexClient elasticIndexClient;
    private final AskService askService;
    private final TaskService taskService;

    public AgenticController(
            EventIngestService ingestService,
            ContextService contextService,
            EventRepository repository,
            SearchService searchService,
            SessionSummaryService summaryService,
            SummaryExportService summaryExportService,
            ProjectService projectService,
            ProjectMeldService projectMeldService,
            LocalAiClient localAiClient,
            ElasticIndexClient elasticIndexClient,
            AskService askService,
            TaskService taskService) {
        this.ingestService = ingestService;
        this.contextService = contextService;
        this.repository = repository;
        this.searchService = searchService;
        this.summaryService = summaryService;
        this.summaryExportService = summaryExportService;
        this.projectService = projectService;
        this.projectMeldService = projectMeldService;
        this.localAiClient = localAiClient;
        this.elasticIndexClient = elasticIndexClient;
        this.askService = askService;
        this.taskService = taskService;
    }

    @PostMapping("/events")
    public IngestResponse ingest(@Valid @RequestBody EventIngestRequest request) {
        return ingestService.ingest(request);
    }

    @GetMapping("/events")
    public EventFeedResponse eventFeed(
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(required = false) String before,
            @RequestParam(required = false) String since,
            @RequestParam(defaultValue = "false") boolean meaningful) {
        return repository.feed(q, meaningful, before, since, safeEventLimit(limit));
    }

    @PostMapping("/decisions")
    public IngestResponse captureDecision(@Valid @RequestBody CaptureDecisionRequest request) {
        return contextService.captureDecision(request);
    }

    @PostMapping("/handoffs")
    public IngestResponse captureHandoff(@Valid @RequestBody CaptureHandoffRequest request) {
        return contextService.captureHandoff(request);
    }

    @GetMapping("/recall")
    public RecallResult recall(
            @RequestParam(required = false) String scope,
            @RequestParam(defaultValue = "168") int withinHours,
            @RequestParam(required = false) List<String> kinds) {
        return contextService.recall(scope, withinHours, kinds);
    }

    @GetMapping("/sessions")
    public List<AgentSession> sessions(@RequestParam(defaultValue = "25") int limit) {
        return repository.recentSessions(safeLimit(limit));
    }

    @GetMapping("/projects")
    public List<ProjectSummary> projects() {
        return projectService.projects();
    }

    @GetMapping("/projects/{projectKey}/sessions")
    public List<AgentSession> projectSessions(
            @PathVariable String projectKey,
            @RequestParam(defaultValue = "100") int limit) {
        return projectService.sessions(projectKey, safeLimit(limit));
    }

    @GetMapping("/projects/{projectKey}/timeline")
    public ProjectTimelineResponse projectTimeline(
            @PathVariable String projectKey,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        return projectService.timeline(projectKey, safeLimit(limit), safeOffset(offset));
    }

    @GetMapping("/projects/{projectKey}/melds")
    public List<ProjectSavedMeld> projectMelds(@PathVariable String projectKey) {
        return projectService.melds(projectKey);
    }

    @PostMapping("/melds")
    public ProjectSavedMeld saveProjectMeld(@RequestBody ProjectMeldSaveRequest request) {
        return projectMeldService.save(request);
    }

    @PostMapping("/projects/{projectKey}/melds/preview")
    public ProjectMeldPreviewResponse previewProjectMeld(
            @PathVariable String projectKey,
            @RequestBody ProjectMeldPreviewRequest request) {
        return projectMeldService.preview(projectKey, request);
    }

    @GetMapping("/sessions/{sessionId}/events")
    public List<AgentEvent> events(@PathVariable String sessionId, @RequestParam(defaultValue = "100") int limit) {
        return repository.eventsForSession(sessionId, safeEventLimit(limit));
    }

    @PostMapping("/sessions/{sessionId}/summarize")
    public AgentSession summarize(@PathVariable String sessionId) {
        return summaryService.summarize(sessionId);
    }

    @PostMapping("/sessions/summarize")
    public AgentSession summarizeByClientSession(
            @RequestParam String source,
            @RequestParam String clientSessionId) {
        return summaryService.summarize(source, clientSessionId);
    }

    @PostMapping("/sessions/summarize-missing")
    public SessionSummaryService.SummaryBackfillResult summarizeMissing(
            @RequestParam(defaultValue = "10") int limit) {
        return summaryService.summarizeMissing(limit);
    }

    @GetMapping("/exports/targets")
    public List<SummaryExportService.ExportTarget> exportTargets() {
        return summaryExportService.targets();
    }

    @PostMapping("/sessions/{sessionId}/exports/{targetId}")
    public SummaryExportService.SummaryExport exportSummary(
            @PathVariable String sessionId,
            @PathVariable String targetId) {
        return summaryExportService.exportSummary(sessionId, targetId);
    }

    @GetMapping("/search")
    public SearchResponse search(@RequestParam String q, @RequestParam(defaultValue = "25") int limit) {
        return searchService.search(q, safeLimit(limit));
    }

    @GetMapping("/ask/status")
    public AskStatus askStatus() {
        return askService.status();
    }

    @GetMapping("/ask/retrieve")
    public AskRetrieveResponse askRetrieve(@RequestParam String q, @RequestParam(defaultValue = "10") int limit) {
        return askService.retrieve(q, safeLimit(limit));
    }

    @PostMapping("/ask")
    public AskResponse ask(@RequestBody AskRequest request) {
        return askService.ask(request);
    }

    @GetMapping("/search/fields")
    public List<Map<String, Object>> searchFields() {
        return searchService.fields();
    }

    @GetMapping("/search/values")
    public List<String> searchValues(
            @RequestParam String field,
            @RequestParam(required = false, defaultValue = "") String prefix,
            @RequestParam(defaultValue = "20") int limit) {
        return searchService.fieldValues(field, prefix, safeValueLimit(limit));
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of(
                "storage", repository.stats(),
                "localAi", localAiClient.health(),
                "elasticsearch", elasticIndexClient.health());
    }

    @GetMapping("/stats")
    public DashboardStats stats() {
        return repository.dashboardStats();
    }

    @GetMapping("/health/local-ai")
    public Object localAiHealth() {
        return localAiClient.health();
    }

    @GetMapping("/health/elasticsearch")
    public Object elasticsearchHealth() {
        return elasticIndexClient.health();
    }

    @PostMapping("/specs")
    public TaskSpec createSpec(@RequestBody CreateSpecRequest request) {
        return taskService.createSpec(request);
    }

    @GetMapping("/specs/{specId}")
    public TaskSpec getSpec(@PathVariable String specId) {
        return taskService.getSpec(requireUuid(specId, "Spec id"));
    }

    @PostMapping("/tasks")
    public TaskChange enqueueTask(@RequestBody EnqueueTaskRequest request) {
        return taskService.enqueueTask(new EnqueueTaskRequest(
                requireUuid(request.specId(), "Spec id"),
                request.title(),
                request.lane(),
                request.priority(),
                request.actor()));
    }

    @GetMapping("/tasks")
    public List<TaskSnapshot> listTasks(
            @RequestParam(required = false) String projectKey,
            @RequestParam(required = false) String lane,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "100") int limit) {
        return taskService.listTasks(new TaskQuery(
                        optionalFilter(projectKey),
                        optionalFilter(lane),
                        optionalStatus(status)))
                .stream()
                .limit(safeTaskLimit(limit))
                .toList();
    }

    @PostMapping("/tasks/claim")
    public org.springframework.http.ResponseEntity<TaskChange> claimNextTask(
            @RequestBody ClaimTaskRequest request) {
        return taskService.claimNextTask(request)
                .map(org.springframework.http.ResponseEntity::ok)
                .orElseGet(() -> org.springframework.http.ResponseEntity.noContent().build());
    }

    @PatchMapping("/tasks/{taskId}")
    public TaskChange updateTaskStatus(
            @PathVariable String taskId,
            @RequestBody TaskStatusBody request) {
        return taskService.updateTaskStatus(new UpdateTaskStatusRequest(
                requireUuid(taskId, "Task id"),
                request.actor(),
                requireStatus(request.status()),
                request.blockedReason()));
    }

    @PostMapping("/tasks/{taskId}/complete")
    public TaskChange completeTask(
            @PathVariable String taskId,
            @RequestBody CompleteTaskBody request) {
        return taskService.completeTask(new CompleteTaskRequest(
                requireUuid(taskId, "Task id"),
                request.actor(),
                request.source(),
                request.clientSessionId(),
                request.summary(),
                request.openLoops(),
                request.nextAction()));
    }

    public record TaskStatusBody(String actor, String status, String blockedReason) {
    }

    public record CompleteTaskBody(
            String actor,
            String source,
            String clientSessionId,
            String summary,
            List<String> openLoops,
            String nextAction) {
    }

    private static int safeLimit(int limit) {
        return Math.max(1, Math.min(limit, 250));
    }

    private static int safeEventLimit(int limit) {
        return Math.max(1, Math.min(limit, 2_000));
    }

    private static int safeValueLimit(int limit) {
        return Math.max(1, Math.min(limit, 50));
    }

    private static int safeOffset(int offset) {
        return Math.max(0, offset);
    }

    private static int safeTaskLimit(int limit) {
        return Math.max(1, Math.min(limit, 250));
    }

    private static String optionalFilter(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static TaskStatus optionalStatus(String value) {
        return value == null || value.isBlank() ? null : parseStatus(value);
    }

    private static TaskStatus requireStatus(String value) {
        if (value == null || value.isBlank()) {
            throw validation("Task status is required");
        }
        return parseStatus(value);
    }

    private static TaskStatus parseStatus(String value) {
        try {
            return TaskStatus.fromValue(value);
        }
        catch (IllegalArgumentException ex) {
            throw validation("Unknown task status: " + value);
        }
    }

    private static String requireUuid(String value, String label) {
        if (value == null || value.isBlank()) {
            throw validation(label + " is required");
        }
        String normalized = value.strip();
        try {
            UUID.fromString(normalized);
            return normalized;
        }
        catch (IllegalArgumentException ex) {
            throw validation(label + " must be a UUID");
        }
    }

    private static TaskDomainException validation(String message) {
        return new TaskDomainException(TaskErrorCode.VALIDATION_FAILED, message, null, null, null);
    }
}
