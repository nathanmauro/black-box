package dev.nathan.sbaagentic.event;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import dev.nathan.sbaagentic.ai.SessionFinalizationService;
import dev.nathan.sbaagentic.config.SbaProperties;
import dev.nathan.sbaagentic.search.EventIndexSink;
import dev.nathan.sbaagentic.session.TitleRank;
import dev.nathan.sbaagentic.session.Titles;

import org.springframework.stereotype.Service;

@Service
public class EventIngestService {

    private final EventRepository repository;
    private final SbaProperties properties;
    private final List<EventIndexSink> indexSinks;
    private final SessionFinalizationService finalizationService;
    private final RedactionService redactionService;

    public EventIngestService(
            EventRepository repository,
            SbaProperties properties,
            List<EventIndexSink> indexSinks,
            SessionFinalizationService finalizationService,
            RedactionService redactionService) {
        this.repository = repository;
        this.properties = properties;
        this.indexSinks = indexSinks;
        this.finalizationService = finalizationService;
        this.redactionService = redactionService;
    }

    public IngestResponse ingest(EventIngestRequest request) {
        EventIngestRequest normalized = normalize(request);
        Instant observedAt = normalized.observedAt() == null ? Instant.now() : normalized.observedAt();
        TitleCandidate title = titleFor(normalized);

        // SQLite is the source of truth: persist atomically first, then fan out to optional indexes.
        // Indexing runs outside the transaction so an external search backend can never hold the
        // database connection open or fail the canonical write.
        EventRepository.Persisted persisted =
                repository.persistEvent(normalized, observedAt, title.value(), title.rank());
        AgentEvent event = persisted.event();

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
        Map<String, Object> metadata = redactMetadata(request.metadata());
        return new EventIngestRequest(
                request.source().trim().toLowerCase(Locale.ROOT),
                request.clientSessionId().trim(),
                blankToNull(request.turnId()),
                request.eventType().trim(),
                blankToNull(request.role()),
                text,
                blankToNull(request.cwd()),
                blankToNull(request.toolName()),
                redactionService.redactDeep(request.toolInput()),
                redactionService.redactDeep(request.toolOutput()),
                metadata,
                request.observedAt());
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
