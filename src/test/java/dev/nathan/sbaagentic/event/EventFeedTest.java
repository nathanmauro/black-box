package dev.nathan.sbaagentic.event;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the SQLite global activity feed contract: newest-first ordering, facet-aware filtering,
 * meaningful-event narrowing, keyset pagination, and live head refetch windows.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite:file:event-feed-test?mode=memory&cache=shared",
        "sba.local-ai.enabled=false",
        "sba.summary.backend=local",
        "sba.elasticsearch.enabled=false"
})
class EventFeedTest {

    @Autowired
    EventIngestService ingestService;

    @Autowired
    EventRepository repository;

    @Test
    void feedReturnsNewestFirstWithSessionAliases() {
        String key = uniqueKey("ordering");
        SeededEvent first = seed(key, "codex", key + "-first", "Decision", "assistant",
                "Same timestamp first " + key, "/tmp/" + key + "/alpha", null,
                Instant.parse("2026-07-01T12:00:00Z"));
        SeededEvent second = seed(key, "claude", key + "-second", "Handoff", "assistant",
                "Same timestamp second " + key, "/tmp/" + key + "/beta", null,
                Instant.parse("2026-07-01T12:00:00Z"));
        SeededEvent older = seed(key, "codex", key + "-older", "Observation", "assistant",
                "Older event " + key, "/tmp/" + key + "/alpha", "Read",
                Instant.parse("2026-07-01T11:59:00Z"));

        List<String> expected = List.of(first, second, older).stream()
                .sorted(Comparator
                        .comparing(SeededEvent::observedAt).reversed()
                        .thenComparing(SeededEvent::id, Comparator.reverseOrder()))
                .map(SeededEvent::id)
                .toList();

        EventFeedResponse response = repository.feed(key, false, null, null, 10);

        assertThat(response.limit()).isEqualTo(10);
        assertThat(response.count()).isEqualTo(3);
        assertThat(response.nextBefore()).isNull();
        assertThat(response.items()).extracting(EventFeedItem::id).containsExactlyElementsOf(expected);
        assertThat(response.items()).allSatisfy(item -> {
            assertThat(item.cwd()).contains(key);
            assertThat(item.sessionTitle()).startsWith("Title ");
        });
    }

    @Test
    void feedHonorsAllFacetTypes() {
        String key = uniqueKey("facets");
        SeededEvent codex = seed(key, "codex-" + key, key + "-codex", "Decision", "assistant",
                "Facet target " + key, "/tmp/" + key + "/alpha", "Edit",
                Instant.parse("2026-07-01T12:00:00Z"));
        SeededEvent claude = seed(key, "claude-" + key, key + "-claude", "Handoff", "assistant",
                "Facet target " + key, "/tmp/" + key + "/beta", "Read",
                Instant.parse("2026-07-01T12:01:00Z"));

        assertThat(repository.feed("source:codex-" + key, false, null, null, 10).items())
                .extracting(EventFeedItem::id)
                .containsExactly(codex.id());
        assertThat(repository.feed("kind:Handoff " + key, false, null, null, 10).items())
                .extracting(EventFeedItem::id)
                .containsExactly(claude.id());
        assertThat(repository.feed("tool:Edit " + key, false, null, null, 10).items())
                .extracting(EventFeedItem::id)
                .containsExactly(codex.id());
        assertThat(repository.feed("project:" + key + "/beta", false, null, null, 10).items())
                .extracting(EventFeedItem::id)
                .containsExactly(claude.id());
    }

    @Test
    void meaningfulFeedUsesStorylinePredicate() {
        String key = uniqueKey("meaningful");
        SeededEvent decision = seed(key, "codex", key + "-decision", "Decision", "agent",
                "Decision survives meaningful filter " + key, "/tmp/" + key, null,
                Instant.parse("2026-07-01T12:00:00Z"));
        seed(key, "codex", key + "-noise", "UserPromptSubmit", "user",
                "User prompt drops from meaningful filter " + key, "/tmp/" + key, null,
                Instant.parse("2026-07-01T12:01:00Z"));

        EventFeedResponse raw = repository.feed(key, false, null, null, 10);
        EventFeedResponse meaningful = repository.feed(key, true, null, null, 10);

        assertThat(raw.items()).hasSize(2);
        assertThat(meaningful.items()).extracting(EventFeedItem::id).containsExactly(decision.id());
    }

    @Test
    void keysetPaginationSplitsObservedAtAndIdWithoutOverlap() {
        String key = uniqueKey("page");
        SeededEvent first = seed(key, "codex", key + "-first", "Decision", "assistant",
                "Paginated tie first " + key, "/tmp/" + key, null,
                Instant.parse("2026-07-01T12:00:00Z"));
        SeededEvent second = seed(key, "codex", key + "-second", "Decision", "assistant",
                "Paginated tie second " + key, "/tmp/" + key, null,
                Instant.parse("2026-07-01T12:00:00Z"));
        SeededEvent older = seed(key, "codex", key + "-older", "Decision", "assistant",
                "Paginated older " + key, "/tmp/" + key, null,
                Instant.parse("2026-07-01T11:59:00Z"));
        List<SeededEvent> expected = List.of(first, second, older).stream()
                .sorted(Comparator
                        .comparing(SeededEvent::observedAt).reversed()
                        .thenComparing(SeededEvent::id, Comparator.reverseOrder()))
                .toList();

        EventFeedResponse pageOne = repository.feed(key, false, null, null, 1);
        EventFeedResponse pageTwo = repository.feed(key, false, pageOne.nextBefore(), null, 2);

        assertThat(pageOne.items()).extracting(EventFeedItem::id).containsExactly(expected.get(0).id());
        assertThat(pageOne.nextBefore()).isEqualTo(cursor(expected.get(0)));
        assertThat(pageTwo.items()).extracting(EventFeedItem::id)
                .containsExactly(expected.get(1).id(), expected.get(2).id());
        assertThat(pageTwo.nextBefore()).isNull();
    }

    @Test
    void sinceUsesAnOverlappingHeadWindow() {
        String key = uniqueKey("since");
        seed(key, "codex", key + "-older", "Decision", "assistant",
                "Older than live anchor " + key, "/tmp/" + key, null,
                Instant.parse("2026-07-01T11:59:00Z"));
        SeededEvent anchor = seed(key, "codex", key + "-anchor", "Decision", "assistant",
                "Live anchor " + key, "/tmp/" + key, null,
                Instant.parse("2026-07-01T12:00:00Z"));
        SeededEvent newer = seed(key, "codex", key + "-newer", "Decision", "assistant",
                "Newer than live anchor " + key, "/tmp/" + key, null,
                Instant.parse("2026-07-01T12:01:00Z"));

        EventFeedResponse response = repository.feed(key, false, null, anchor.observedAt().toString(), 10);

        assertThat(response.items()).extracting(EventFeedItem::id).containsExactly(newer.id(), anchor.id());
    }

    @Test
    void malformedCursorsFailClosed() {
        assertThatThrownBy(() -> repository.feed(null, false, "not-a-cursor", null, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("before");
        assertThatThrownBy(() -> repository.feed(null, false, null, "not-an-instant", 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("since");
    }

    private SeededEvent seed(
            String key,
            String source,
            String clientSessionId,
            String eventType,
            String role,
            String text,
            String cwd,
            String toolName,
            Instant observedAt) {
        String title = "Title " + clientSessionId;
        String eventId = ingestService.ingest(new EventIngestRequest(
                source, clientSessionId, "turn-" + clientSessionId, eventType, role,
                text, cwd, toolName, null, null,
                Map.of("title", title, "feedKey", key),
                observedAt)).eventId();
        return new SeededEvent(eventId, observedAt);
    }

    private static String uniqueKey(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().replace("-", "");
    }

    private static String cursor(SeededEvent event) {
        return event.observedAt() + "|" + event.id();
    }

    private record SeededEvent(String id, Instant observedAt) {
    }
}
