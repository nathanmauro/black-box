package dev.nathan.sbaagentic.web;

import java.util.List;
import java.util.Map;

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
    private final LocalAiClient localAiClient;
    private final ElasticIndexClient elasticIndexClient;

    public AgenticController(
            EventIngestService ingestService,
            ContextService contextService,
            EventRepository repository,
            SearchService searchService,
            SessionSummaryService summaryService,
            LocalAiClient localAiClient,
            ElasticIndexClient elasticIndexClient) {
        this.ingestService = ingestService;
        this.contextService = contextService;
        this.repository = repository;
        this.searchService = searchService;
        this.summaryService = summaryService;
        this.localAiClient = localAiClient;
        this.elasticIndexClient = elasticIndexClient;
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

    @GetMapping("/sessions/{sessionId}/events")
    public List<AgentEvent> events(@PathVariable String sessionId, @RequestParam(defaultValue = "100") int limit) {
        return repository.eventsForSession(sessionId, safeLimit(limit));
    }

    @PostMapping("/sessions/{sessionId}/summarize")
    public AgentSession summarize(@PathVariable String sessionId) {
        return summaryService.summarize(sessionId);
    }

    @GetMapping("/search")
    public SearchResponse search(@RequestParam String q, @RequestParam(defaultValue = "25") int limit) {
        return searchService.search(q, safeLimit(limit));
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
}
