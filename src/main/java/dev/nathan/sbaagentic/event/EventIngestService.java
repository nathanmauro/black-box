package dev.nathan.sbaagentic.event;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import dev.nathan.sbaagentic.config.SbaProperties;
import dev.nathan.sbaagentic.search.EventIndexSink;
import dev.nathan.sbaagentic.session.AgentSession;

import org.springframework.stereotype.Service;

@Service
public class EventIngestService {

    private final EventRepository repository;
    private final SbaProperties properties;
    private final List<EventIndexSink> indexSinks;

    public EventIngestService(EventRepository repository, SbaProperties properties, List<EventIndexSink> indexSinks) {
        this.repository = repository;
        this.properties = properties;
        this.indexSinks = indexSinks;
    }

    public IngestResponse ingest(EventIngestRequest request) {
        EventIngestRequest normalized = normalize(request);
        Instant observedAt = normalized.observedAt() == null ? Instant.now() : normalized.observedAt();
        AgentSession session = repository.findOrCreateSession(normalized, observedAt, titleFor(normalized));
        AgentEvent event = repository.saveEvent(normalized, session, observedAt);
        AgentSession updatedSession = repository.findSessionById(session.id()).orElse(session);

        boolean indexed = false;
        for (EventIndexSink sink : indexSinks) {
            indexed = sink.index(updatedSession, event) || indexed;
        }

        return new IngestResponse(
                event.id(),
                event.sessionId(),
                event.source(),
                event.clientSessionId(),
                event.eventType(),
                indexed);
    }

    private EventIngestRequest normalize(EventIngestRequest request) {
        String text = truncate(blankToNull(request.text()));
        Map<String, Object> metadata = request.metadata() == null ? Map.of() : request.metadata();
        return new EventIngestRequest(
                request.source().trim().toLowerCase(Locale.ROOT),
                request.clientSessionId().trim(),
                blankToNull(request.turnId()),
                request.eventType().trim(),
                blankToNull(request.role()),
                text,
                blankToNull(request.cwd()),
                blankToNull(request.toolName()),
                request.toolInput(),
                request.toolOutput(),
                metadata,
                request.observedAt());
    }

    private String titleFor(EventIngestRequest request) {
        Object title = request.metadata().get("title");
        if (title instanceof String value && !value.isBlank()) {
            return truncateTitle(value);
        }
        if (request.text() != null && !request.text().isBlank()) {
            return truncateTitle(firstLine(request.text()));
        }
        if (request.toolName() != null && !request.toolName().isBlank()) {
            return truncateTitle(request.toolName() + " via " + request.eventType());
        }
        return truncateTitle(request.source() + " " + request.eventType());
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

    private static String truncateTitle(String value) {
        String compact = Objects.requireNonNullElse(value, "Untitled session")
                .replaceAll("\\s+", " ")
                .trim();
        return compact.length() <= 96 ? compact : compact.substring(0, 93) + "...";
    }

    private static String firstLine(String value) {
        int newline = value.indexOf('\n');
        return newline >= 0 ? value.substring(0, newline) : value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
