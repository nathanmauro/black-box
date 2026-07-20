package dev.nathan.sbaagentic.recording.internal.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import dev.nathan.sbaagentic.recording.AgentEvent;
import dev.nathan.sbaagentic.recording.AgentSession;
import dev.nathan.sbaagentic.recording.EventIngestRequest;
import dev.nathan.sbaagentic.recording.EventRecorded;
import dev.nathan.sbaagentic.recording.IngestionProperties;
import dev.nathan.sbaagentic.recording.SessionStopped;
import dev.nathan.sbaagentic.recording.internal.application.port.RecordingStore;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RecordingLifecyclePublicationTest {

    @Test
    void persistsBeforePublishingOrderedRecordedAndStoppedEvents() {
        List<String> sequence = new ArrayList<>();
        Instant observedAt = Instant.parse("2026-07-20T13:00:00Z");
        AgentSession session = new AgentSession(
                "session-id", "codex", "client-id", "Stop", "/repo", null,
                observedAt, observedAt, 1);
        AgentEvent event = new AgentEvent(
                "event-id", session.id(), "codex", "client-id", "turn-1", "Stop",
                "assistant", "done", null, null, null, Map.of(), observedAt);
        RecordingStore store = (request, at, title, titleRank) -> {
            sequence.add("persisted");
            return new RecordingStore.Persisted(session, event);
        };
        EventIngestService service = new EventIngestService(
                store,
                new IngestionProperties(),
                new RedactionService(new IngestionProperties()),
                published -> {
                    if (published instanceof EventRecorded recorded) {
                        sequence.add("recorded");
                        recorded.markIndexed();
                    }
                    else if (published instanceof SessionStopped) {
                        sequence.add("stopped");
                    }
                });

        var response = service.ingest(new EventIngestRequest(
                "codex", "client-id", "turn-1", "Stop", "assistant", "done", "/repo",
                null, null, null, Map.of(), observedAt));

        assertThat(sequence).containsExactly("persisted", "recorded", "stopped");
        assertThat(response.indexed()).isTrue();
    }
}
