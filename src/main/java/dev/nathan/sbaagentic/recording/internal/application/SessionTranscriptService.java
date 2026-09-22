package dev.nathan.sbaagentic.recording.internal.application;

import dev.nathan.sbaagentic.query.EventQuery;
import dev.nathan.sbaagentic.query.EventQuery.Field;
import dev.nathan.sbaagentic.recording.AgentEvent;
import dev.nathan.sbaagentic.recording.AgentSession;
import dev.nathan.sbaagentic.recording.EventFeedItem;
import dev.nathan.sbaagentic.recording.EventFeedResponse;
import dev.nathan.sbaagentic.recording.RecordingCatalog;
import dev.nathan.sbaagentic.recording.SessionTranscriptOperations;
import dev.nathan.sbaagentic.recording.SessionTranscriptResponse;
import dev.nathan.sbaagentic.recording.internal.application.port.TranscriptMessageSource;
import dev.nathan.sbaagentic.recording.internal.application.port.TranscriptRead;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SessionTranscriptService implements SessionTranscriptOperations {

    private static final Duration DUPLICATE_WINDOW = Duration.ofMinutes(2);
    private static final Comparator<AgentEvent> NEWEST_FIRST = Comparator.comparing(AgentEvent::observedAt)
            .thenComparing(AgentEvent::id)
            .reversed();

    private final RecordingCatalog repository;
    private final TranscriptMessageSource messageSource;
    private final Clock clock;

    public SessionTranscriptService(RecordingCatalog repository, TranscriptMessageSource messageSource, Clock clock) {
        this.repository = repository;
        this.messageSource = messageSource;
        this.clock = clock;
    }

    @Override
    public SessionTranscriptResponse transcript(String sessionId, String query, String before, int limit) {
        AgentSession session = repository
                .findSessionById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found"));
        int safeLimit = Math.max(1, Math.min(limit, 250));
        EventFeedResponse recorded = repository.feedForSession(sessionId, query, before, safeLimit);
        TranscriptRead transcript = messageSource.read(session, repository.transcriptPathsForSession(sessionId));
        List<AgentEvent> recordedConversation =
                transcript.messages().isEmpty() ? List.of() : repository.conversationEventsForSession(sessionId);
        EventQuery parsedQuery = EventQuery.parse(query);
        Cursor cursor = parseCursor(before);

        List<AgentEvent> combined = new ArrayList<>();
        for (EventFeedItem item : recorded.items()) {
            combined.add(toEvent(item));
        }
        for (AgentEvent message : transcript.messages()) {
            if (isBefore(message, cursor)
                    && matches(message, session, parsedQuery)
                    && recordedConversation.stream().noneMatch(recordedEvent -> duplicates(recordedEvent, message))) {
                combined.add(message);
            }
        }
        combined.sort(NEWEST_FIRST);

        boolean hasMore = combined.size() > safeLimit || recorded.nextBefore() != null;
        List<AgentEvent> page =
                combined.size() > safeLimit ? List.copyOf(combined.subList(0, safeLimit)) : List.copyOf(combined);
        String nextBefore = hasMore && !page.isEmpty() ? cursorFor(page.get(page.size() - 1)) : null;

        return new SessionTranscriptResponse(
                sessionId,
                transcript.available(),
                transcript.complete(),
                transcript.reason(),
                safeLimit,
                page.size(),
                page,
                nextBefore);
    }

    private boolean matches(AgentEvent event, AgentSession session, EventQuery query) {
        String source = lower(event.source());
        String kind = lower(event.eventType());
        String cwd = lower(session.cwd());
        if (!included(source, query.values(Field.SOURCE)))

            return false;

        if (excluded(source, query.excluded(Field.SOURCE)))

            return false;

        if (!matchesTranscriptKind(event, query.values(Field.KIND)))

            return false;

        if (excluded(kind, query.excluded(Field.KIND)))

            return false;

        if (!query.values(Field.TOOL).isEmpty())

            return false;

        if (!includedBySubstring(cwd, query.values(Field.PROJECT)))

            return false;

        if (excludedBySubstring(cwd, query.excluded(Field.PROJECT)))

            return false;

        if (!query.values(Field.PROJECT_EXACT).isEmpty()
                && query.values(Field.PROJECT_EXACT).stream()
                        .map(SessionTranscriptService::lower)
                        .noneMatch(cwd::equals))

                            return false;
        if (query.excluded(Field.PROJECT_EXACT).stream()
                .map(SessionTranscriptService::lower)
                .anyMatch(cwd::equals))

                    return false;
        String haystack = lower(event.text());
        if (query.freeTerms().stream().map(SessionTranscriptService::lower).anyMatch(term -> !haystack.contains(term)))

            return false;
        if (query.sinceSpec().isPresent()
                && event.observedAt().isBefore(query.sinceSpec().orElseThrow().resolve(clock)))

                    return false;
        if (query.untilSpec().isPresent()) {
            Instant until = query.untilSpec().orElseThrow().resolve(clock);
            if (query.untilSpec().orElseThrow().exclusiveEnd()
                    ? !event.observedAt().isBefore(until)
                    : event.observedAt().isAfter(until))

                        return false;
        }

        return true;
    }

    private static boolean matchesTranscriptKind(AgentEvent event, List<String> includedKinds) {
        if (includedKinds.isEmpty())

            return true;

        String roleAlias = "user".equals(event.role()) ? "userpromptsubmit" : "assistantmessage";
        String kind = lower(event.eventType());

        return includedKinds.stream()
                .map(SessionTranscriptService::lower)
                .anyMatch(candidate -> candidate.equals(kind) || candidate.equals(roleAlias));
    }

    private static boolean included(String value, List<String> included) {

        return included.isEmpty()
                || included.stream().map(SessionTranscriptService::lower).anyMatch(value::equals);
    }

    private static boolean excluded(String value, List<String> excluded) {

        return excluded.stream().map(SessionTranscriptService::lower).anyMatch(value::equals);
    }

    private static boolean includedBySubstring(String value, List<String> included) {

        return included.isEmpty()
                || included.stream().map(SessionTranscriptService::lower).anyMatch(value::contains);
    }

    private static boolean excludedBySubstring(String value, List<String> excluded) {

        return excluded.stream().map(SessionTranscriptService::lower).anyMatch(value::contains);
    }

    private static boolean duplicates(AgentEvent recorded, AgentEvent transcript) {
        String recordedRole = conversationRole(recorded);
        if (recordedRole == null || !recordedRole.equals(transcript.role()))

            return false;

        if (!normalize(recorded.text()).equals(normalize(transcript.text())))

            return false;

        if (recorded.turnId() != null
                && transcript.turnId() != null
                && recorded.turnId().equals(transcript.turnId()))

                    return true;

        return Duration.between(recorded.observedAt(), transcript.observedAt())
                        .abs()
                        .compareTo(DUPLICATE_WINDOW)
                <= 0;
    }

    private static String conversationRole(AgentEvent event) {
        String role = lower(event.role());
        if ("user".equals(role) || "assistant".equals(role))

            return role;

        String type = lower(event.eventType()).replaceAll("[^a-z0-9]", "");
        if (type.equals("userpromptsubmit") || type.equals("beforesubmitprompt"))

            return "user";

        if (event.text() != null
                && !event.text().isBlank()
                && (type.equals("stop")
                        || type.equals("assistantmessage")
                        || type.equals("agentmessage")
                        || type.equals("agentresponse")
                        || type.equals("finalresponse")))

                            return "assistant";

        return null;
    }

    private static AgentEvent toEvent(EventFeedItem item) {
        Map<String, Object> metadata = withoutRawHook(item.metadata());
        String text = duplicatesRawToolResponse(item) ? null : item.text();

        return new AgentEvent(
                item.id(),
                item.sessionId(),
                item.source(),
                item.clientSessionId(),
                item.turnId(),
                item.eventType(),
                item.role(),
                text,
                item.toolName(),
                item.toolInputJson(),
                item.toolOutputJson(),
                metadata,
                item.observedAt());
    }

    private static Map<String, Object> withoutRawHook(Map<String, Object> metadata) {
        if (metadata == null || !metadata.containsKey("rawHook"))

            return metadata == null ? Map.of() : metadata;

        Map<String, Object> projected = new LinkedHashMap<>(metadata);
        projected.remove("rawHook");

        return Map.copyOf(projected);
    }

    private static boolean duplicatesRawToolResponse(EventFeedItem item) {
        if (item.text() == null || item.text().isBlank() || item.toolOutputJson() == null)

            return false;

        Object rawHook = item.metadata() == null ? null : item.metadata().get("rawHook");
        if (!(rawHook instanceof Map<?, ?> raw))

            return false;

        Object response = firstPresent(raw, "tool_response", "toolResponse", "tool_output", "toolOutput");

        return response instanceof String value
                && value.trim().equals(item.text().trim());
    }

    private static Object firstPresent(Map<?, ?> values, String... keys) {
        for (String key : keys) {
            if (values.containsKey(key))

                return values.get(key);
        }

        return null;
    }

    private static boolean isBefore(AgentEvent event, Cursor cursor) {
        if (cursor == null)

            return true;

        int time = event.observedAt().compareTo(cursor.observedAt());

        return time < 0 || (time == 0 && event.id().compareTo(cursor.id()) < 0);
    }

    private static Cursor parseCursor(String value) {
        if (value == null || value.isBlank())

            return null;

        String[] parts = value.split("\\|", 2);
        if (parts.length != 2 || parts[1].isBlank()) {
            throw new IllegalArgumentException("Invalid before cursor. Expected '<observedAt>|<id>'.");
        }
        try {

            return new Cursor(Instant.parse(parts[0]), parts[1]);
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException("Invalid before cursor. Expected '<observedAt>|<id>'.", ex);
        }
    }

    private static String cursorFor(AgentEvent event) {

        return event.observedAt() + "|" + event.id();
    }

    private static String normalize(String value) {

        return lower(value).replaceAll("\\s+", " ").trim();
    }

    private static String lower(String value) {

        return String.valueOf(value == null ? "" : value).toLowerCase(Locale.ROOT);
    }

    private record Cursor(Instant observedAt, String id) {}
}
