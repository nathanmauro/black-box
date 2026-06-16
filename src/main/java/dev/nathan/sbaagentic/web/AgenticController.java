package dev.nathan.sbaagentic.web;

import java.util.List;
import java.util.Map;

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
import dev.nathan.sbaagentic.event.EventIngestRequest;
import dev.nathan.sbaagentic.event.EventIngestService;
import dev.nathan.sbaagentic.event.EventRepository;
import dev.nathan.sbaagentic.event.IngestResponse;
import dev.nathan.sbaagentic.exporting.SummaryExportService;
import dev.nathan.sbaagentic.project.ProjectService;
import dev.nathan.sbaagentic.project.ProjectMeldPreviewRequest;
import dev.nathan.sbaagentic.project.ProjectMeldPreviewResponse;
import dev.nathan.sbaagentic.project.ProjectMeldService;
import dev.nathan.sbaagentic.project.ProjectSummary;
import dev.nathan.sbaagentic.project.ProjectTimelineResponse;
import dev.nathan.sbaagentic.search.ElasticIndexClient;
import dev.nathan.sbaagentic.search.SearchResponse;
import dev.nathan.sbaagentic.search.SearchService;
import dev.nathan.sbaagentic.session.AgentSession;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
            AskService askService) {
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
    }

    @PostMapping("/events")
    public IngestResponse ingest(@Valid @RequestBody EventIngestRequest request) {
        return ingestService.ingest(request);
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
    public List<Object> projectMelds(@PathVariable String projectKey) {
        return projectService.melds(projectKey);
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

    @GetMapping("/health/local-ai")
    public Object localAiHealth() {
        return localAiClient.health();
    }

    @GetMapping("/health/elasticsearch")
    public Object elasticsearchHealth() {
        return elasticIndexClient.health();
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
}
