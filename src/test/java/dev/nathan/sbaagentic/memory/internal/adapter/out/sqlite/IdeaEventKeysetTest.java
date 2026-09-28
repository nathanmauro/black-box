package dev.nathan.sbaagentic.memory.internal.adapter.out.sqlite;

import static org.assertj.core.api.Assertions.assertThat;

import dev.nathan.sbaagentic.memory.internal.application.port.IdeaEventReader;
import dev.nathan.sbaagentic.memory.internal.application.port.IdeaEventReader.TypedEvent;
import dev.nathan.sbaagentic.recording.EventIngestRequest;
import dev.nathan.sbaagentic.recording.EventRecorder;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** Keyset paging over typed events visits every row once, in chronological (not string) order. */
@SpringBootTest(
        properties = {
            "spring.datasource.url=jdbc:sqlite:${java.io.tmpdir}/bb-idea-keyset-test-${random.uuid}.db",
            "sba.local-ai.enabled=false",
            "sba.elasticsearch.enabled=false",
            "sba.memory.embedding.enabled=false"
        })
class IdeaEventKeysetTest {

    @Autowired
    EventRecorder recorder;

    @Autowired
    IdeaEventReader reader;

    @Test
    void pagesCoverEveryRowOnceAcrossVariablePrecisionAndTiedInstants() {
        String eventType = "KeysetProbe" + UUID.randomUUID().toString().substring(0, 8);
        // Stored as ISO strings: "…:00Z" sorts after "…:00.5Z" as text, but is chronologically first.
        List<Instant> instants = List.of(
                Instant.parse("2026-09-01T10:00:00Z"),
                Instant.parse("2026-09-01T10:00:00.123456789Z"),
                Instant.parse("2026-09-01T10:00:00.5Z"),
                Instant.parse("2026-09-01T10:00:01Z"),
                Instant.parse("2026-09-01T10:00:02Z"),
                Instant.parse("2026-09-01T10:00:02Z"),
                Instant.parse("2026-09-01T10:00:02Z"));
        for (int i = 0; i < instants.size(); i++) {
            recorder.ingest(new EventIngestRequest(
                    "keyset",
                    "keyset-" + eventType,
                    null,
                    eventType,
                    "assistant",
                    "probe " + i,
                    "/tmp/keyset",
                    null,
                    null,
                    null,
                    Map.of(),
                    instants.get(i)));
        }

        List<TypedEvent> all = reader.eventsOfType(eventType, null, 100);
        assertThat(all).hasSize(instants.size());
        for (int i = 1; i < all.size(); i++) {
            Instant newer = all.get(i - 1).event().observedAt();
            Instant older = all.get(i).event().observedAt();
            assertThat(older).isBeforeOrEqualTo(newer);
            if (older.equals(newer)) {
                assertThat(all.get(i).event().id())
                        .isLessThan(all.get(i - 1).event().id());
            }
        }

        List<String> paged = new ArrayList<>();
        IdeaEventReader.Cursor before = null;
        for (int guard = 0; guard < 10; guard++) {
            List<TypedEvent> page = reader.eventsOfType(eventType, null, before, 2);
            page.forEach(row -> paged.add(row.event().id()));
            if (page.size() < 2) {
                break;
            }
            before = page.getLast().cursor();
        }

        assertThat(paged)
                .containsExactlyElementsOf(
                        all.stream().map(row -> row.event().id()).toList());
    }
}
