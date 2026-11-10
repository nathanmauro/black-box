package dev.nathan.sbaagentic.recording.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nathan.sbaagentic.recording.AgentEvent;
import dev.nathan.sbaagentic.recording.AgentSession;
import dev.nathan.sbaagentic.recording.EventFeedResponse;
import dev.nathan.sbaagentic.recording.RecordingCatalog;
import dev.nathan.sbaagentic.recording.internal.application.port.TranscriptMessageSource;
import dev.nathan.sbaagentic.recording.internal.application.port.TranscriptRead;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Opt-in service benchmark: cached transcript and catalog fixtures, no database or model calls. */
@EnabledIfSystemProperty(named = "sba.transcript.benchmark", matches = "true")
class SessionTranscriptBenchmark {
    private static final Instant START = Instant.parse("2026-11-10T12:00:00Z");

    @Test
    void measureUniqueAndDuplicateHeavyTranscripts() {
        int runs = Integer.getInteger("sba.transcript.benchmark.runs", 3);
        String sizes = System.getProperty("sba.transcript.benchmark.sizes", "500,2000,10000");
        for (String value : sizes.split(",")) {
            int size = Integer.parseInt(value);
            for (boolean duplicates : List.of(false, true)) {
                SessionTranscriptService service = fixture(size, duplicates);
                service.transcript("fixture", null, null, 250, false);
                long[] nanos = new long[runs];
                for (int run = 0; run < runs; run++) {
                    long started = System.nanoTime();
                    var response = service.transcript("fixture", null, null, 250, false);
                    nanos[run] = System.nanoTime() - started;
                    assertThat(response.events()).hasSize(Math.min(250, duplicates ? (size + 4) / 5 : size));
                }
                Arrays.sort(nanos);
                System.out.printf(
                        "transcript-benchmark size=%d shape=%s runs=%d median_ms=%.3f%n",
                        size, duplicates ? "80-percent-duplicates" : "unique", runs, nanos[runs / 2] / 1_000_000.0);
            }
        }
    }

    private SessionTranscriptService fixture(int size, boolean duplicates) {
        RecordingCatalog catalog = mock(RecordingCatalog.class);
        TranscriptMessageSource source = mock(TranscriptMessageSource.class);
        AgentSession session =
                new AgentSession("fixture", "claude", "fixture", "Fixture", "/fixture", null, START, START, size, null);
        List<AgentEvent> recorded = new ArrayList<>(size);
        List<AgentEvent> transcript = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            String text = "Recorded answer " + index + " with  spaced\twords";
            recorded.add(event("recorded-" + index, text, index));
            boolean duplicate = duplicates && index % 5 != 0;
            transcript.add(event("transcript-" + index, duplicate ? text : "Missing " + text, index));
        }
        when(catalog.findSessionById("fixture")).thenReturn(Optional.of(session));
        when(catalog.transcriptPathsForSession("fixture")).thenReturn(List.of());
        when(catalog.conversationEventsForSession("fixture")).thenReturn(recorded);
        when(catalog.feedForSession("fixture", null, null, 250, false))
                .thenReturn(new EventFeedResponse(250, 0, List.of(), null));
        when(source.read(session, List.of())).thenReturn(new TranscriptRead(true, true, null, transcript));

        return new SessionTranscriptService(catalog, source, Clock.fixed(START, ZoneOffset.UTC), new ObjectMapper());
    }

    private AgentEvent event(String id, String text, int index) {

        return new AgentEvent(
                id,
                "fixture",
                "claude",
                "fixture",
                "turn-" + index,
                "AssistantMessage",
                "assistant",
                text,
                null,
                null,
                null,
                Map.of(),
                START.plusSeconds(index));
    }
}
