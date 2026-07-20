package dev.nathan.sbaagentic.recording.internal.adapter.in.web;

import java.util.List;

import dev.nathan.sbaagentic.project.ProjectScopeOperations;
import dev.nathan.sbaagentic.recording.AgentEvent;
import dev.nathan.sbaagentic.recording.EventFeedResponse;
import dev.nathan.sbaagentic.recording.EventIngestRequest;
import dev.nathan.sbaagentic.recording.EventRecorder;
import dev.nathan.sbaagentic.recording.RecordingCatalog;
import dev.nathan.sbaagentic.recording.IngestResponse;
import dev.nathan.sbaagentic.recording.AgentSession;
import dev.nathan.sbaagentic.recording.QueryFacets;

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
public class EventController {

    private final EventRecorder ingestService;
    private final RecordingCatalog repository;
    private final ProjectScopeOperations projectScopes;

    public EventController(
            EventRecorder ingestService,
            RecordingCatalog repository,
            ProjectScopeOperations projectScopes) {
        this.ingestService = ingestService;
        this.repository = repository;
        this.projectScopes = projectScopes;
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
        QueryFacets facets = QueryFacets.parse(q);
        List<String> scopes = facets.groupCwd() == null
                ? List.of()
                : projectScopes.scopesFor(facets.groupCwd());
        return repository.feed(q, meaningful, before, since, scopes, safeEventLimit(limit));
    }

    @GetMapping("/sessions")
    public List<AgentSession> sessions(@RequestParam(defaultValue = "25") int limit) {
        return repository.recentSessions(safeLimit(limit));
    }

    @GetMapping("/sessions/{sessionId}/events")
    public List<AgentEvent> events(
            @PathVariable String sessionId,
            @RequestParam(defaultValue = "100") int limit) {
        return repository.eventsForSession(sessionId, safeEventLimit(limit));
    }

    private static int safeLimit(int limit) {
        return Math.max(1, Math.min(limit, 250));
    }

    private static int safeEventLimit(int limit) {
        return Math.max(1, Math.min(limit, 2_000));
    }
}
