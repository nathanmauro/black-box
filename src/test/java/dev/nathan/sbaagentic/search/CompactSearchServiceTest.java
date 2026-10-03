package dev.nathan.sbaagentic.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nathan.sbaagentic.memory.CompactPageRequest;
import dev.nathan.sbaagentic.memory.internal.application.CompactSearchJson;
import dev.nathan.sbaagentic.memory.internal.application.CompactSearchService;
import dev.nathan.sbaagentic.memory.internal.application.port.CompactEventReader;
import dev.nathan.sbaagentic.memory.internal.application.port.CompactEventReader.Candidate;
import dev.nathan.sbaagentic.memory.internal.application.port.CompactEventReader.PageQuery;
import dev.nathan.sbaagentic.memory.internal.application.port.CompactEventReader.PageRow;
import dev.nathan.sbaagentic.memory.internal.application.port.SearchIndex;
import dev.nathan.sbaagentic.project.ProjectScopeOperations;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Base64;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CompactSearchServiceTest {
    private final CompactEventReader events = mock(CompactEventReader.class);
    private final SearchIndex index = mock(SearchIndex.class);
    private final ProjectScopeOperations projects = mock(ProjectScopeOperations.class);
    private final CompactSearchService service = new CompactSearchService(
            events, index, projects, Clock.fixed(Instant.parse("2026-03-09T12:00:00Z"), ZoneId.of("America/New_York")));

    @BeforeEach
    void defaults() {
        when(events.searchCompact(any(), anyList(), anyInt(), any(), nullable(String.class)))
                .thenReturn(List.of());
        when(index.searchCompact(anyString(), anyInt()))
                .thenReturn(new SearchIndex.CompactResults("disabled", List.of()));
    }

    @Test
    void bytesIncludeEscapesUnicodeAndMetadataAndSingleHugeItem() {
        when(events.searchCompact(any(), anyList(), anyInt(), any(), nullable(String.class)))
                .thenReturn(IntStream.range(0, 50)
                        .mapToObj(i -> candidate("event-" + i, "\"\\\n🦉".repeat(200)))
                        .toList());
        for (int budget : List.of(2048, 5000, 24000, 64000)) {
            var result = service.search("fixture", 50, budget, null, false);
            assertThat(CompactSearchJson.write(result).getBytes(StandardCharsets.UTF_8).length)
                    .isLessThanOrEqualTo(budget);
            assertThat(result.truncated()).isTrue();
            assertThat(result.items()).isNotEmpty();
            assertThat(result.items())
                    .allSatisfy(hit -> assertThat(hit.excerpt()
                                    .codePointCount(0, hit.excerpt().length()))
                            .isLessThanOrEqualTo(600));
        }
    }

    @Test
    void canonicalCopyWinsWithoutMergingIndependentDecisions() {
        var first = candidate("a", "Same decision");
        var second = candidate("b", "Same decision");
        when(events.searchCompact(any(), anyList(), anyInt(), any(), nullable(String.class)))
                .thenReturn(List.of(first, second));
        when(index.searchCompact(anyString(), anyInt()))
                .thenReturn(new SearchIndex.CompactResults(
                        "searched", List.of(candidate("a", "STALE"), candidate("c", "Indexed only"))));
        var result = service.search("fixture", 50, null, null, true);
        assertThat(result.items()).extracting(hit -> hit.eventId()).containsExactly("a", "b", "c");
        assertThat(result.items().getFirst().backends()).containsExactly("local", "elastic");
        assertThat(result.items().getFirst().excerpt()).isEqualTo("Same decision");
        assertThat(result.items().getLast().sourceReference().status()).isEqualTo("unresolved");
        assertThat(result.items().getLast().sourceReference().eventPath()).isNull();
    }

    @Test
    void allRecognizedFiltersSuppressIndexAndResolveOneDstClock() {
        for (String query : List.of(
                "source:codex",
                "kind:Decision",
                "tool:Read",
                "project:fixture",
                "session:abc",
                "since:2026-03-08 until:2026-03-08",
                "last:7d",
                "NOT source:claude")) {
            var result = service.search(query, null, null, null, null);
            assertThat(result.coverage().get("elastic").status()).isEqualTo("skipped_filters");
        }
        verifyNoInteractions(index);
        var dst = service.search("since:2026-03-08 until:2026-03-08", null, null, null, null);
        assertThat(dst.appliedFilters())
                .containsEntry("sinceInclusive", "2026-03-08T05:00:00Z")
                .containsEntry("untilExclusive", "2026-03-09T04:00:00Z");
    }

    @Test
    void malformedAndOversizedIdentitiesAreNotInventedOrShortenedIntoLinks() {
        when(events.searchCompact(any(), anyList(), anyInt(), any(), nullable(String.class)))
                .thenReturn(List.of(new Candidate(
                        "x".repeat(257),
                        "session",
                        "codex-voice:bad:123",
                        "codex",
                        "Decision",
                        "assistant",
                        null,
                        "fixture")));
        var hit = service.search("fixture", 1, null, null, false).items().getFirst();
        assertThat(hit.eventId()).isNull();
        assertThat(hit.sourceReference().status()).isEqualTo("unresolved");
        assertThat(hit.sourceReference().externalTaskId()).isNull();
    }

    @Test
    void unsupportedAndInvalidDatesFailBeforeReadsButLiteralColonTextWorks() {
        for (String query : List.of(
                "before:2026-08-18",
                "since:invalid",
                "until:2026-02-30",
                "last:yesterday",
                "NOT since:today",
                "NOT until:today",
                "NOT last:7d")) {
            assertThat(service.search(query, null, null, null, null).status()).isEqualTo("invalid_query");
        }
        verifyNoInteractions(events, index);
        for (String query : List.of("\"before:2026-08-18\"", "https://example.invalid", "/repo/file:12")) {
            assertThat(service.search(query, null, null, null, null).status()).isEqualTo("ok");
        }
    }

    @Test
    void queryMetadataCannotEvadeSmallByteBudget() {
        var result = service.search("source:" + "🦉".repeat(400), null, 2048, null, false);
        assertThat(result.status()).isEqualTo("invalid_request");
        assertThat(CompactSearchJson.write(result).getBytes(StandardCharsets.UTF_8).length)
                .isLessThanOrEqualTo(2048);
        verifyNoInteractions(events, index);
    }

    @Test
    void indexFailureIsDifferentFromEmptyAndCandidateCapIsExplicit() {
        when(index.searchCompact(anyString(), anyInt()))
                .thenReturn(new SearchIndex.CompactResults("unavailable", List.of()));
        var empty = service.search("fixture", null, null, null, null);
        assertThat(empty.count()).isZero();
        assertThat(empty.coverage().get("elastic").status()).isEqualTo("unavailable");
        assertThat(empty.coverage().get("local").candidateLimitReached()).isFalse();
    }

    @Test
    void pageAdvancesFromLastDeliveredHitNeverFromDroppedOrExtraRows() throws Exception {
        List<PageRow> rows = IntStream.range(0, 4)
                .mapToObj(i -> new PageRow(
                        candidate("event-" + i, "🦉".repeat(600)),
                        "1000002026-03-01T00:00:00.00000000" + (9 - i),
                        "event-" + i))
                .toList();
        when(events.pageCompact(any(), anyInt())).thenReturn(rows);
        var page = service.searchPage(new CompactPageRequest(List.of("owl"), null, null, null, null, 3, 4000));
        assertThat(page.status()).isEqualTo("ok");
        assertThat(page.items()).extracting(hit -> hit.eventId()).containsExactly("event-0");
        assertThat(page.hasMore()).isTrue();
        assertThat(page.budgetLimited()).isTrue();
        assertThat(CompactSearchJson.write(page).getBytes(StandardCharsets.UTF_8).length)
                .isLessThanOrEqualTo(4000);
        JsonNode cursor = new ObjectMapper().readTree(Base64.getUrlDecoder().decode(page.nextBefore()));
        assertThat(cursor.path("i").asText()).isEqualTo("event-0");
        assertThat(cursor.path("k").asText()).isEqualTo(rows.getFirst().orderKey());
        assertThat(cursor.path("u").asText()).isEqualTo("2026-03-09T12:00:00Z");

        service.searchPage(new CompactPageRequest(List.of("owl"), null, null, null, page.nextBefore(), 3, 4000));
        var query = ArgumentCaptor.forClass(PageQuery.class);
        verify(events, times(2)).pageCompact(query.capture(), eq(4));
        assertThat(query.getValue().beforeId()).isEqualTo("event-0");
        assertThat(query.getValue().beforeKey()).isEqualTo(rows.getFirst().orderKey());
        assertThat(query.getValue().untilKey()).isEqualTo("1000002026-03-09T12:00:00.000000000");
    }

    @Test
    void irreducibleHitFailsWithoutCursorAndInvalidInputsNeverRead() {
        String escaped = "\u0001".repeat(256);
        when(events.pageCompact(any(), anyInt()))
                .thenReturn(List.of(new PageRow(
                        new Candidate(
                                escaped,
                                escaped,
                                escaped,
                                "manual",
                                "Decision",
                                "assistant",
                                "2026-08-17T00:00:00Z",
                                "x"),
                        "1000002026-08-17T00:00:00.000000000",
                        "event-0")));
        var failed = service.searchPage(new CompactPageRequest(List.of("x"), null, null, null, null, 1, 2048));
        assertThat(failed.status()).isEqualTo("budget_exceeded");
        assertThat(failed.items()).isEmpty();
        assertThat(failed.nextBefore()).isNull();
        assertThat(failed.hasMore()).isFalse();
        assertThat(CompactSearchJson.write(failed).getBytes(StandardCharsets.UTF_8).length)
                .isLessThanOrEqualTo(2048);

        reset(events);
        for (var request : List.of(
                new CompactPageRequest(List.of(), null, null, null, null, null, null),
                new CompactPageRequest(List.of("a", "\ud83e"), null, null, null, null, null, null),
                new CompactPageRequest(List.of("a\u007f"), null, null, null, null, null, null),
                new CompactPageRequest(List.of("x".repeat(513)), null, null, null, null, null, null),
                new CompactPageRequest(List.of("a"), "/repo\n", null, null, null, null, null),
                new CompactPageRequest(List.of("a"), null, "s".repeat(257), null, null, null, null),
                new CompactPageRequest(List.of("a"), null, null, "2026-08-17", null, null, null),
                new CompactPageRequest(List.of("a"), null, null, null, "not-a-cursor", null, null))) {
            assertThat(service.searchPage(request).status()).isNotEqualTo("ok");
        }
        verifyNoInteractions(events, index);
    }

    @Test
    void canonicalProjectIdentityMatchesProductKeyAndTermsNormalize() {
        when(events.pageCompact(any(), anyInt())).thenReturn(List.of());
        var page = service.searchPage(new CompactPageRequest(
                List.of("b", "a", "b"), " /repo/// ", "session-1", "2026-08-17T01:00:00+01:00", null, null, null));
        assertThat(page.appliedFilters())
                .containsEntry("terms", List.of("a", "b"))
                .containsEntry("projectExact", "/repo")
                .containsEntry("sessionId", "session-1")
                .containsEntry("until", "2026-08-17T00:00:00Z");
        assertThat(page.hasMore()).isFalse();
        assertThat(page.nextBefore()).isNull();
        verifyNoInteractions(index);
    }

    private static Candidate candidate(String id, String text) {

        return new Candidate(id, "session", "client", "manual", "Decision", "assistant", "2026-08-17T00:00:00Z", text);
    }
}
