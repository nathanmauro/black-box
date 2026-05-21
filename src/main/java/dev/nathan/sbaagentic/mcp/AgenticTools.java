package dev.nathan.sbaagentic.mcp;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import dev.nathan.sbaagentic.ai.LocalAiClient;
import dev.nathan.sbaagentic.event.EventIngestRequest;
import dev.nathan.sbaagentic.event.EventIngestService;
import dev.nathan.sbaagentic.event.EventRepository;
import dev.nathan.sbaagentic.event.IngestResponse;
import dev.nathan.sbaagentic.search.SearchResponse;
import dev.nathan.sbaagentic.search.SearchService;
import dev.nathan.sbaagentic.session.AgentSession;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class AgenticTools {

    private final EventRepository repository;
    private final EventIngestService ingestService;
    private final SearchService searchService;
    private final LocalAiClient localAiClient;

    public AgenticTools(
            EventRepository repository,
            EventIngestService ingestService,
            SearchService searchService,
            LocalAiClient localAiClient) {
        this.repository = repository;
        this.ingestService = ingestService;
        this.searchService = searchService;
        this.localAiClient = localAiClient;
    }

    @Tool(description = "List recent local agent sessions captured from Claude Code, Codex, or manual CLI input.")
    public List<AgentSession> recentSessions(
            @ToolParam(description = "Maximum number of sessions to return. Use 10 unless the user asks for more.") int limit) {
        return repository.recentSessions(Math.max(1, Math.min(limit, 50)));
    }

    @Tool(description = "Search captured local agent events and sessions.")
    public SearchResponse searchSessions(
            @ToolParam(description = "Search query text.") String query,
            @ToolParam(description = "Maximum number of results to return.") int limit) {
        return searchService.search(query, Math.max(1, Math.min(limit, 50)));
    }

    @Tool(description = "Capture an observation or note into the local agentic event store.")
    public IngestResponse captureObservation(
            @ToolParam(description = "Source client, such as claude, codex, or manual.") String source,
            @ToolParam(description = "Client session id or stable grouping key.") String clientSessionId,
            @ToolParam(description = "Event type, such as Observation, Decision, Todo, or Handoff.") String eventType,
            @ToolParam(description = "Text to capture.") String text) {
        return ingestService.ingest(new EventIngestRequest(
                source,
                clientSessionId,
                null,
                eventType,
                "assistant",
                text,
                System.getProperty("user.dir"),
                null,
                null,
                null,
                Map.of("title", eventType + " from " + source),
                Instant.now()));
    }

    @Tool(description = "Check the local AI backend status for LM Studio or another OpenAI-compatible local model server.")
    public Object localModelStatus() {
        return localAiClient.health();
    }
}
