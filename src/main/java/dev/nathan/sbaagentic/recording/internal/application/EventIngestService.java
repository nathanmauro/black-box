package dev.nathan.sbaagentic.recording.internal.application;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import dev.nathan.sbaagentic.ai.SessionFinalizationService;
import dev.nathan.sbaagentic.config.SbaProperties;
import dev.nathan.sbaagentic.project.ProjectAliasService;
import dev.nathan.sbaagentic.recording.AgentEvent;
import dev.nathan.sbaagentic.recording.EventIngestRequest;
import dev.nathan.sbaagentic.recording.EventRecorder;
import dev.nathan.sbaagentic.recording.IngestResponse;
import dev.nathan.sbaagentic.search.EventIndexSink;
import dev.nathan.sbaagentic.recording.TitleRank;
import dev.nathan.sbaagentic.recording.Titles;
import dev.nathan.sbaagentic.recording.internal.application.port.RecordingStore;

import org.springframework.stereotype.Service;

@Service
public class EventIngestService implements EventRecorder {

    private final RecordingStore repository;
    private final SbaProperties properties;
    private final List<EventIndexSink> indexSinks;
    private final SessionFinalizationService finalizationService;
    private final RedactionService redactionService;
    private final ProjectAliasService projectAliasService;

    public EventIngestService(
            RecordingStore repository,
            SbaProperties properties,
            List<EventIndexSink> indexSinks,
            SessionFinalizationService finalizationService,
            RedactionService redactionService,
            ProjectAliasService projectAliasService) {
        this.repository = repository;
        this.properties = properties;
        this.indexSinks = indexSinks;
        this.finalizationService = finalizationService;
        this.redactionService = redactionService;
        this.projectAliasService = projectAliasService;
    }

    @Override
    public IngestResponse ingest(EventIngestRequest request) {
        EventIngestRequest normalized = normalize(request);
        Instant observedAt = normalized.observedAt() == null ? Instant.now() : normalized.observedAt();
        TitleCandidate title = titleFor(normalized);

        // SQLite is the source of truth: persist atomically first, then fan out to optional indexes.
        // Indexing runs outside the transaction so an external search backend can never hold the
        // database connection open or fail the canonical write.
        RecordingStore.Persisted persisted =
                repository.persistEvent(normalized, observedAt, title.value(), title.rank());
        AgentEvent event = persisted.event();
        projectAliasService.discoverVerifiedAlias(persisted.session().cwd());

        boolean indexed = false;
        for (EventIndexSink sink : indexSinks) {
            indexed = sink.index(persisted.session(), event) || indexed;
        }
        finalizationService.summarizeAfterFinalEvent(persisted.session(), event);

        return new IngestResponse(
                event.id(),
                event.sessionId(),
                event.source(),
                event.clientSessionId(),
                event.eventType(),
                indexed);
    }

    private EventIngestRequest normalize(EventIngestRequest request) {
        String text = truncate(blankToNull(redactionService.redact(request.text())));
        String eventType = request.eventType().trim();
        String role = normalizeRole(request.role(), eventType, text);
        Map<String, Object> metadata = redactMetadata(request.metadata());
        return new EventIngestRequest(
                request.source().trim().toLowerCase(Locale.ROOT),
                request.clientSessionId().trim(),
                blankToNull(request.turnId()),
                eventType,
                role,
                text,
                blankToNull(request.cwd()),
                blankToNull(request.toolName()),
                redactionService.redactDeep(request.toolInput()),
                redactionService.redactDeep(request.toolOutput()),
                metadata,
                request.observedAt());
    }

    private String normalizeRole(String role, String eventType, String text) {
        String normalizedRole = blankToNull(role);
        if (normalizedRole == null || !normalizedRole.equalsIgnoreCase("agent")) {
            return normalizedRole;
        }

        return switch (normalizeEventType(eventType)) {
            case "userpromptsubmit", "beforesubmitprompt" -> "user";
            case "stop", "assistantmessage" -> text == null ? normalizedRole : "assistant";
            case "pretooluse", "posttooluse" -> "tool";
            default -> normalizedRole;
        };
    }

    private String normalizeEventType(String eventType) {
        return eventType.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> redactMetadata(Map<String, Object> metadata) {
        if (metadata == null) {
            return Map.of();
        }
        return (Map<String, Object>) redactionService.redactDeep(metadata);
    }

    private TitleCandidate titleFor(EventIngestRequest request) {
        Object title = request.metadata().get("title");
        if (title instanceof String value && !value.isBlank()) {
            return new TitleCandidate(Titles.sanitize(value), TitleRank.EXPLICIT);
        }
        if (request.text() != null && !request.text().isBlank()) {
            return new TitleCandidate(Titles.sanitize(Titles.firstLine(request.text())), TitleRank.TEXT);
        }
        if (request.toolName() != null && !request.toolName().isBlank()) {
            return new TitleCandidate(Titles.sanitize(request.toolName() + " via " + request.eventType()), TitleRank.TOOL);
        }
        return new TitleCandidate(Titles.sanitize(request.source() + " " + request.eventType()), TitleRank.FALLBACK);
    }

    private String truncate(String value) {
        if (value == null) {
            return null;
        }
        int max = properties.getIngestion().getMaxTextLength();
        if (value.length() <= max) {
            return value;
        }
        return value.substring(0, max) + "\n[truncated]";
    }

    private record TitleCandidate(String value, int rank) {
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
