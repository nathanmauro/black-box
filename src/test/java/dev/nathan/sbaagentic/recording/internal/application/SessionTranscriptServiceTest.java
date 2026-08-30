package dev.nathan.sbaagentic.recording.internal.application;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import dev.nathan.sbaagentic.recording.AgentEvent;
import dev.nathan.sbaagentic.recording.AgentSession;
import dev.nathan.sbaagentic.recording.EventFeedItem;
import dev.nathan.sbaagentic.recording.EventFeedResponse;
import dev.nathan.sbaagentic.recording.RecordingCatalog;
import dev.nathan.sbaagentic.recording.SessionTranscriptResponse;
import dev.nathan.sbaagentic.recording.internal.application.port.TranscriptMessageSource;
import dev.nathan.sbaagentic.recording.internal.application.port.TranscriptRead;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SessionTranscriptServiceTest {

    @Mock
    RecordingCatalog repository;

    @Mock
    TranscriptMessageSource messageSource;

    SessionTranscriptService service;
    AgentSession session;

    @BeforeEach
    void setUp() {
        service = new SessionTranscriptService(
                repository, messageSource, Clock.fixed(Instant.parse("2026-08-30T14:00:00Z"), ZoneOffset.UTC));
        session = new AgentSession(
                "session-1", "codex", "client-1", "Session", "/tmp/project", null,
                Instant.parse("2026-08-30T12:00:00Z"), Instant.parse("2026-08-30T13:00:00Z"), 2, null);
    }

    @Test
    void combinesRecordedToolsWithMissingMessagesAndPrefersRecordedDuplicates() {
        when(repository.findSessionById("session-1")).thenReturn(Optional.of(session));
        when(repository.transcriptPathsForSession("session-1")).thenReturn(List.of("known.jsonl"));
        when(repository.conversationEventsForSession("session-1")).thenReturn(List.of(
                event("prompt-1", "UserPromptSubmit", "user", "Show it", "2026-08-30T12:00:00Z")));
        when(repository.feedForSession("session-1", "session:other", null, 10)).thenReturn(new EventFeedResponse(
                10,
                2,
                List.of(
                        item("tool-1", "PostToolUse", "tool", null, "Read", "2026-08-30T12:02:00Z"),
                        item("prompt-1", "UserPromptSubmit", "user", "Show it", null, "2026-08-30T12:00:00Z")),
                null));
        when(messageSource.read(session, List.of("known.jsonl"))).thenReturn(new TranscriptRead(
                true,
                true,
                null,
                List.of(
                        event("tx:user", "TranscriptMessage", "user", "Show it", "2026-08-30T12:00:01Z"),
                        event("tx:assistant", "TranscriptMessage", "assistant", "Here it is", "2026-08-30T12:03:00Z"))));

        SessionTranscriptResponse result = service.transcript("session-1", "session:other", null, 10);

        assertThat(result.available()).isTrue();
        assertThat(result.events()).extracting(AgentEvent::id)
                .containsExactly("tx:assistant", "tool-1", "prompt-1");
        verify(repository).feedForSession("session-1", "session:other", null, 10);
    }

    @Test
    void filtersTranscriptMessagesByQueryAndCursorAndReturnsNotFound() {
        when(repository.findSessionById("session-1")).thenReturn(Optional.of(session));
        when(repository.transcriptPathsForSession("session-1")).thenReturn(List.of());
        when(repository.conversationEventsForSession("session-1")).thenReturn(List.of());
        String before = "2026-08-30T12:03:00Z|cursor";
        when(repository.feedForSession("session-1", "missing answer", before, 5))
                .thenReturn(new EventFeedResponse(5, 0, List.of(), null));
        when(messageSource.read(session, List.of())).thenReturn(new TranscriptRead(
                true,
                true,
                null,
                List.of(
                        event("newer", "TranscriptMessage", "assistant", "missing answer", "2026-08-30T12:04:00Z"),
                        event("older", "TranscriptMessage", "assistant", "missing answer", "2026-08-30T12:02:00Z"),
                        event("wrong", "TranscriptMessage", "assistant", "different", "2026-08-30T12:01:00Z"))));

        SessionTranscriptResponse result = service.transcript("session-1", "missing answer", before, 5);
        assertThat(result.events()).extracting(AgentEvent::id).containsExactly("older");

        when(repository.findSessionById("missing")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.transcript("missing", null, null, 10))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void deduplicatesTranscriptMessagesAgainstRecordedRowsOutsideTheCurrentPage() {
        when(repository.findSessionById("session-1")).thenReturn(Optional.of(session));
        when(repository.transcriptPathsForSession("session-1")).thenReturn(List.of("known.jsonl"));
        AgentEvent recordedAnswer = event(
                "recorded-answer", "AssistantMessage", "assistant", "Same answer", "2026-08-30T12:02:00Z");
        when(repository.conversationEventsForSession("session-1")).thenReturn(List.of(recordedAnswer));
        when(messageSource.read(session, List.of("known.jsonl"))).thenReturn(new TranscriptRead(
                true,
                true,
                null,
                List.of(event(
                        "tx:answer", "TranscriptMessage", "assistant", "Same answer", "2026-08-30T12:03:00Z"))));
        when(repository.feedForSession("session-1", null, null, 1)).thenReturn(new EventFeedResponse(
                1,
                1,
                List.of(item("tool-1", "PostToolUse", "tool", null, "Read", "2026-08-30T12:02:30Z")),
                "more-recorded-events"));

        SessionTranscriptResponse first = service.transcript("session-1", null, null, 1);
        assertThat(first.events()).extracting(AgentEvent::id).containsExactly("tool-1");
        assertThat(first.nextBefore()).isEqualTo("2026-08-30T12:02:30Z|tool-1");

        when(repository.feedForSession("session-1", null, first.nextBefore(), 1)).thenReturn(new EventFeedResponse(
                1,
                1,
                List.of(new EventFeedItem(
                        recordedAnswer.id(), recordedAnswer.sessionId(), recordedAnswer.source(),
                        recordedAnswer.clientSessionId(), recordedAnswer.turnId(), recordedAnswer.eventType(),
                        recordedAnswer.role(), recordedAnswer.text(), null, null, null, Map.of(),
                        recordedAnswer.observedAt(), "/tmp/project", "Session")),
                null));

        SessionTranscriptResponse second = service.transcript("session-1", null, first.nextBefore(), 1);
        assertThat(second.events()).extracting(AgentEvent::id).containsExactly("recorded-answer");
    }

    private EventFeedItem item(
            String id, String eventType, String role, String text, String toolName, String observedAt) {
        return new EventFeedItem(
                id, "session-1", "codex", "client-1", "turn-1", eventType, role, text,
                toolName, toolName == null ? null : "{}", toolName == null ? null : "{}", Map.of(),
                Instant.parse(observedAt), "/tmp/project", "Session");
    }

    private AgentEvent event(String id, String eventType, String role, String text, String observedAt) {
        return new AgentEvent(
                id, "session-1", "codex", "client-1", "turn-1", eventType, role, text,
                null, null, null, Map.of("transcript", true), Instant.parse(observedAt));
    }
}
