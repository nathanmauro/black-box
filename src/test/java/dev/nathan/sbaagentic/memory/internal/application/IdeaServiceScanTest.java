package dev.nathan.sbaagentic.memory.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import dev.nathan.sbaagentic.memory.internal.application.port.IdeaEventReader;
import dev.nathan.sbaagentic.recording.AgentEvent;
import dev.nathan.sbaagentic.recording.RecordingCaptureOperations;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The Ideas list and migration read every Idea, not a newest-N window. */
class IdeaServiceScanTest {

    private static final Instant BASE = Instant.parse("2026-09-01T00:00:00Z");
    private static final int IDEAS = 6_000;

    @Test
    void migrationStaysIdempotentAndRevisionsStayWholePastManyPages() {
        PagingReader reader = new PagingReader();
        reader.add(event(
                "obs-old",
                "Observation",
                "[Idea] Old one\n- legs: 3",
                Map.of("kind", "observation", "repo", "/x/repo"),
                BASE.minusSeconds(86_400)));
        for (int i = 0; i < IDEAS; i++) {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("kind", "idea");
            metadata.put("ideaKey", i == 1 || i == IDEAS - 1 ? "shared" : "key-" + i);
            metadata.put("title", "Idea " + i);
            metadata.put("status", i == IDEAS - 1 ? "tracked" : "untouched");
            metadata.put("repo", "/x/repo");
            if (i == 0) {
                metadata.put("migratedFrom", "obs-old");
            }
            reader.add(event("idea-%05d".formatted(i), "Idea", "[Idea] Idea " + i, metadata, BASE.plusSeconds(i)));
        }
        RecordingCaptureOperations captureOperations = mock(RecordingCaptureOperations.class);
        IdeaService service = new IdeaService(reader, captureOperations, scope -> List.of());

        IdeaMigrationResult result = service.migrateObservations(true);

        // The oldest Idea (far outside any newest-5,000 window) still marks its observation migrated.
        assertThat(result.created()).isZero();
        assertThat(result.skipped()).isEqualTo(1);
        assertThat(result.candidates()).singleElement().satisfies(candidate -> {
            assertThat(candidate.alreadyMigrated()).isTrue();
            assertThat(candidate.createdEventId()).isEqualTo("idea-00000");
        });
        verifyNoInteractions(captureOperations);

        IdeaListResponse shared = service.list(new IdeaListQuery(null, null, null, null, "shared", null));
        assertThat(shared.items()).singleElement().satisfies(idea -> {
            assertThat(idea.eventId()).isEqualTo("idea-%05d".formatted(IDEAS - 1));
            assertThat(idea.status()).isEqualTo("tracked");
            assertThat(idea.revisions()).isEqualTo(2);
            assertThat(idea.firstCapturedAt()).isEqualTo(BASE.plusSeconds(1));
        });
        IdeaListResponse oldest = service.list(new IdeaListQuery(null, null, null, null, "key-0", null));
        assertThat(oldest.items()).extracting(IdeaView::eventId).contains("idea-00000");
        assertThat(reader.pagesRead).isGreaterThan(2 * (IDEAS / IdeaService.SCAN_PAGE_SIZE));
    }

    private static AgentEvent event(
            String id, String eventType, String text, Map<String, Object> metadata, Instant observedAt) {

        return new AgentEvent(
                id,
                "session-1",
                "claude",
                "client-1",
                null,
                eventType,
                "assistant",
                text,
                null,
                null,
                null,
                metadata,
                observedAt);
    }

    /** In-memory keyset reader with the adapter's newest-first (instant, id) order. */
    private static final class PagingReader implements IdeaEventReader {

        private static final Comparator<TypedEvent> NEWEST_FIRST = Comparator.comparing(
                        (TypedEvent row) -> row.event().observedAt())
                .thenComparing(row -> row.event().id())
                .reversed();

        private final List<TypedEvent> rows = new ArrayList<>();
        private int pagesRead;

        void add(AgentEvent event) {
            rows.add(new TypedEvent(event, "/x/repo"));
        }

        @Override
        public List<TypedEvent> eventsOfType(String eventType, String textPrefix, Cursor before, int limit) {
            pagesRead++;

            return rows.stream()
                    .filter(row -> row.event().eventType().equals(eventType))
                    .filter(row -> textPrefix == null || row.event().text().startsWith(textPrefix))
                    .filter(row -> before == null || isAfter(row, before))
                    .sorted(NEWEST_FIRST)
                    .limit(limit)
                    .toList();
        }

        private static boolean isAfter(TypedEvent row, Cursor before) {
            int byTime = row.event().observedAt().compareTo(before.observedAt());

            return byTime < 0 || (byTime == 0 && row.event().id().compareTo(before.id()) < 0);
        }
    }
}
