package dev.nathan.sbaagentic.recording;

import dev.nathan.sbaagentic.recording.internal.adapter.out.sqlite.RecordingSqlStore;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.nathan.sbaagentic.project.ProjectAliasRequest;
import dev.nathan.sbaagentic.project.internal.application.ProjectAliasService;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the SQLite global activity feed contract: newest-first ordering, facet-aware filtering,
 * meaningful-event narrowing, keyset pagination, and live head refetch windows.
 */
@SpringBootTest(properties = {
        // A temp file DB takes the production WAL + busy_timeout path; cache=shared
        // memory throws SQLITE_LOCKED on writer collisions, ignoring busy_timeout.
        "spring.datasource.url=jdbc:sqlite:${java.io.tmpdir}/bb-event-feed-test-${random.uuid}.db",
        "sba.local-ai.enabled=false",
        "sba.summary.backend=local",
        "sba.elasticsearch.enabled=false",
        "sba.memory.embedding.enabled=false"
})
class EventFeedTest {

    /** Fixed server clock so keyword time tokens (today/yesterday) resolve deterministically —
     * seeding and resolution share one "now", eliminating the midnight-crossing race. */
    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.parse("2026-08-20T15:30:00Z"), ZoneId.of("America/New_York"));

    @TestConfiguration
    static class FixedClockConfig {

        @Bean
        @Primary
        Clock fixedClock() {
            return FIXED_CLOCK;
        }
    }

    @Autowired
    EventRecorder ingestService;

    @Autowired
    RecordingSqlStore repository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    ProjectAliasService projectAliasService;

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
    void feedHonorsNegativeFacets() {
        String key = uniqueKey("negative");
        SeededEvent decision = seed(key, "codex", key + "-decision", "Decision", "assistant",
                "Negative facet target " + key, "/tmp/" + key + "/alpha", null,
                Instant.parse("2026-07-01T12:00:00Z"));
        SeededEvent tool = seed(key, "codex", key + "-tool", "PostToolUse", "assistant",
                "Negative facet tool " + key, "/tmp/" + key + "/alpha", "Edit",
                Instant.parse("2026-07-01T12:01:00Z"));
        SeededEvent otherProject = seed(key, "claude", key + "-other", "Decision", "assistant",
                "Negative facet other project " + key, "/tmp/" + key + "/beta", null,
                Instant.parse("2026-07-01T12:02:00Z"));

        assertThat(repository.feed("NOT kind:PostToolUse " + key, false, null, null, 10).items())
                .extracting(EventFeedItem::id)
                .contains(decision.id(), otherProject.id())
                .doesNotContain(tool.id());

        assertThat(repository.feed("-project:" + key + "/beta " + key, false, null, null, 10).items())
                .extracting(EventFeedItem::id)
                .contains(decision.id())
                .doesNotContain(otherProject.id());
    }

    @Test
    void feedHonorsExactProjectFacetWithoutPrefixLeakageAndNoProjectRows() {
        String key = uniqueKey("exact");
        String source = "codex-" + key;
        SeededEvent app = seed(key, source, key + "-app", "Decision", "assistant",
                "Exact project app " + key, "/tmp/" + key + "/app", null,
                Instant.parse("2026-07-01T12:00:00Z"));
        SeededEvent appOther = seed(key, source, key + "-app-other", "Decision", "assistant",
                "Exact project app other " + key, "/tmp/" + key + "/app-other", null,
                Instant.parse("2026-07-01T12:01:00Z"));
        SeededEvent noProject = seed(key, source, key + "-no-project", "Decision", "assistant",
                "Exact project no cwd " + key, null, null,
                Instant.parse("2026-07-01T12:02:00Z"));
        SeededEvent blankProject = seed(key, source, key + "-blank-project", "Decision", "assistant",
                "Exact project blank cwd " + key, "/tmp/" + key + "/placeholder", null,
                Instant.parse("2026-07-01T12:03:00Z"));
        jdbcTemplate.update("UPDATE agent_sessions SET cwd = '   ' WHERE client_session_id = ?", key + "-blank-project");

        assertThat(repository.feed("source:" + source + " project_exact:/tmp/" + key + "/app", false, null, null, 10).items())
                .extracting(EventFeedItem::id)
                .containsExactly(app.id())
                .doesNotContain(appOther.id());

        assertThat(repository.feed("source:" + source + " project_exact:__no_project__", false, null, null, 10).items())
                .extracting(EventFeedItem::id)
                .containsExactly(blankProject.id(), noProject.id())
                .doesNotContain(app.id());
    }

    @Test
    void projectGroupFacetIncludesAliasesWhileExactRemainsRawExact() {
        String key = uniqueKey("group");
        String source = "codex-" + key;
        String primary = "/tmp/" + key + "/primary";
        String alias = "/tmp/" + key + "/linked";
        SeededEvent primaryEvent = seed(key, source, key + "-primary", "Decision", "assistant",
                "Primary project event " + key, primary, null,
                Instant.parse("2026-07-01T12:00:00Z"));
        SeededEvent aliasEvent = seed(key, source, key + "-alias", "Decision", "assistant",
                "Aliased project event " + key, alias, null,
                Instant.parse("2026-07-01T12:01:00Z"));
        projectAliasService.put(new ProjectAliasRequest(alias, primary));

        assertThat(repository.feed(
                        "source:" + source + " project_exact:" + primary, false, null, null, 10).items())
                .extracting(EventFeedItem::id)
                .containsExactly(primaryEvent.id())
                .doesNotContain(aliasEvent.id());
        assertThat(repository.feed(
                        "source:" + source + " project_group:" + primary,
                        false,
                        null,
                        null,
                        projectAliasService.scopesFor(primary),
                        10).items())
                .extracting(EventFeedItem::id)
                .containsExactly(aliasEvent.id(), primaryEvent.id());

        projectAliasService.delete(alias);
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
    void sessionFacetResolvesServerIdAndClientSessionId() {
        String key = uniqueKey("session");
        SeededEvent mine = seed(key, "codex", key + "-mine", "Decision", "assistant",
                "Session facet target " + key, "/tmp/" + key, null,
                Instant.parse("2026-07-01T12:00:00Z"));
        SeededEvent other = seed(key, "codex", key + "-other", "Decision", "assistant",
                "Session facet other " + key, "/tmp/" + key, null,
                Instant.parse("2026-07-01T12:01:00Z"));

        String serverSessionId = repository.feed(key, false, null, null, 10).items().stream()
                .filter(item -> (key + "-mine").equals(item.clientSessionId()))
                .findFirst().orElseThrow()
                .sessionId();

        assertThat(repository.feed("session:" + serverSessionId, false, null, null, 10).items())
                .extracting(EventFeedItem::id)
                .containsExactly(mine.id())
                .doesNotContain(other.id());
        assertThat(repository.feed("session:" + key + "-mine", false, null, null, 10).items())
                .extracting(EventFeedItem::id)
                .containsExactly(mine.id());
        assertThat(repository.feed("session:" + key + "-nowhere", false, null, null, 10).items())
                .isEmpty();
    }

    @Test
    void grammarSinceAndUntilBoundObservedAtInclusively() {
        String key = uniqueKey("bounds");
        seed(key, "codex", key + "-early", "Decision", "assistant",
                "Before the window " + key, "/tmp/" + key, null,
                Instant.parse("2026-07-01T12:00:00Z"));
        SeededEvent inside = seed(key, "codex", key + "-inside", "Decision", "assistant",
                "Inside the window " + key, "/tmp/" + key, null,
                Instant.parse("2026-07-01T12:01:00Z"));
        seed(key, "codex", key + "-late", "Decision", "assistant",
                "After the window " + key, "/tmp/" + key, null,
                Instant.parse("2026-07-01T12:02:00Z"));

        EventFeedResponse response = repository.feed(
                key + " since:2026-07-01T12:01:00Z until:2026-07-01T12:01:00Z", false, null, null, 10);

        assertThat(response.items()).extracting(EventFeedItem::id).containsExactly(inside.id());
    }

    @Test
    void untilDateIncludesTheWholeNamedDayDownToItsLastWholeSecond() {
        String key = uniqueKey("untilday");
        ZoneId zone = FIXED_CLOCK.getZone();
        // Whole-second timestamp: Instant.toString() emits no fraction ("…59Z"), which an
        // inclusive "…59.999Z" bound would lexicographically exclude — the strict next-day-start
        // bound must keep it.
        SeededEvent lastSecond = seed(key, "codex", key + "-day1", "Decision", "assistant",
                "Last second of the named day " + key, "/tmp/" + key, null,
                LocalDate.of(2026, 7, 1).atTime(23, 59, 59).atZone(zone).toInstant());
        SeededEvent nextDayStart = seed(key, "codex", key + "-day2", "Decision", "assistant",
                "Exactly midnight after " + key, "/tmp/" + key, null,
                LocalDate.of(2026, 7, 2).atStartOfDay(zone).toInstant());

        assertThat(repository.feed(key + " until:2026-07-01", false, null, null, 10).items())
                .extracting(EventFeedItem::id)
                .containsExactly(lastSecond.id());
        assertThat(repository.feed(key + " since:2026-07-02", false, null, null, 10).items())
                .extracting(EventFeedItem::id)
                .containsExactly(nextDayStart.id());
    }

    @Test
    void untilYesterdayIncludesAllOfYesterday() {
        String key = uniqueKey("yday");
        ZoneId zone = FIXED_CLOCK.getZone();
        LocalDate today = LocalDate.now(FIXED_CLOCK);
        SeededEvent yesterdayEvent = seed(key, "codex", key + "-yesterday", "Decision", "assistant",
                "Yesterday noon " + key, "/tmp/" + key, null,
                today.minusDays(1).atTime(12, 0).atZone(zone).toInstant());
        SeededEvent todayEvent = seed(key, "codex", key + "-today", "Decision", "assistant",
                "Today " + key, "/tmp/" + key, null,
                today.atTime(12, 0).atZone(zone).toInstant());

        assertThat(repository.feed(key + " until:yesterday", false, null, null, 10).items())
                .extracting(EventFeedItem::id)
                .containsExactly(yesterdayEvent.id())
                .doesNotContain(todayEvent.id());
    }

    @Test
    void negatedExactProjectFacetExcludesThatProjectOnly() {
        String key = uniqueKey("notexact");
        String source = "codex-" + key;
        SeededEvent app = seed(key, source, key + "-app", "Decision", "assistant",
                "Negated exact app " + key, "/tmp/" + key + "/app", null,
                Instant.parse("2026-07-01T12:00:00Z"));
        SeededEvent other = seed(key, source, key + "-other", "Decision", "assistant",
                "Negated exact other " + key, "/tmp/" + key + "/other", null,
                Instant.parse("2026-07-01T12:01:00Z"));

        assertThat(repository.feed(
                        "source:" + source + " -project_exact:/tmp/" + key + "/app", false, null, null, 10).items())
                .extracting(EventFeedItem::id)
                .containsExactly(other.id())
                .doesNotContain(app.id());
    }

    @Test
    void isAllOverridesMeaningfulTrueOnTheWire() {
        String key = uniqueKey("isall");
        SeededEvent decision = seed(key, "codex", key + "-decision", "Decision", "agent",
                "Decision passes meaningful " + key, "/tmp/" + key, null,
                Instant.parse("2026-07-01T12:00:00Z"));
        SeededEvent noise = seed(key, "codex", key + "-noise", "UserPromptSubmit", "user",
                "Prompt drops under meaningful " + key, "/tmp/" + key, null,
                Instant.parse("2026-07-01T12:01:00Z"));

        assertThat(repository.feed(key, true, null, null, 10).items())
                .extracting(EventFeedItem::id)
                .containsExactly(decision.id());
        assertThat(repository.feed(key + " is:all", true, null, null, 10).items())
                .extracting(EventFeedItem::id)
                .containsExactly(noise.id(), decision.id());
    }

    @Test
    void commaOrAndRepeatedTokensCompileToInLists() {
        String key = uniqueKey("commaor");
        SeededEvent codex = seed(key, "codex-" + key, key + "-codex", "Decision", "assistant",
                "Comma OR codex " + key, "/tmp/" + key, null,
                Instant.parse("2026-07-01T12:00:00Z"));
        SeededEvent claude = seed(key, "claude-" + key, key + "-claude", "Decision", "assistant",
                "Comma OR claude " + key, "/tmp/" + key, null,
                Instant.parse("2026-07-01T12:01:00Z"));
        SeededEvent gemini = seed(key, "gemini-" + key, key + "-gemini", "Decision", "assistant",
                "Comma OR gemini " + key, "/tmp/" + key, null,
                Instant.parse("2026-07-01T12:02:00Z"));

        assertThat(repository.feed("source:codex-" + key + ",claude-" + key + " " + key,
                        false, null, null, 10).items())
                .extracting(EventFeedItem::id)
                .containsExactly(claude.id(), codex.id());
        assertThat(repository.feed("source:codex-" + key + " source:claude-" + key + " " + key,
                        false, null, null, 10).items())
                .extracting(EventFeedItem::id)
                .containsExactly(claude.id(), codex.id());
        assertThat(repository.feed("-source:codex-" + key + ",claude-" + key + " " + key,
                        false, null, null, 10).items())
                .extracting(EventFeedItem::id)
                .containsExactly(gemini.id());
    }

    @Test
    void freeTextMatchesPerTermAcrossSearchedColumns() {
        String key = uniqueKey("perterm");
        String alpha = "alphaterm" + key;
        String bravo = "bravoterm" + key;
        SeededEvent both = seed(key, "codex", key + "-both", "Decision", "assistant",
                "Has " + alpha + " and " + bravo + " together", "/tmp/" + key, null,
                Instant.parse("2026-07-01T12:00:00Z"));
        seed(key, "codex", key + "-alpha-only", "Decision", "assistant",
                "Has only " + alpha + " here", "/tmp/" + key, null,
                Instant.parse("2026-07-01T12:01:00Z"));
        SeededEvent crossColumn = seed(key, "codex", key + "-cross", "PostToolUse", "assistant",
                "Tool row with " + alpha + " in text", "/tmp/" + key, "Read",
                Instant.parse("2026-07-01T12:02:00Z"));

        assertThat(repository.feed(alpha + " " + bravo, false, null, null, 10).items())
                .extracting(EventFeedItem::id)
                .containsExactly(both.id());
        assertThat(repository.feed(alpha + " read", false, null, null, 10).items())
                .extracting(EventFeedItem::id)
                .containsExactly(crossColumn.id());
    }

    @Test
    void grammarTimeBoundsComposeWithCursorAndMeaningful() {
        String key = uniqueKey("compose");
        seed(key, "codex", key + "-before", "Decision", "agent",
                "Before window " + key, "/tmp/" + key, null,
                Instant.parse("2026-07-01T11:59:00Z"));
        SeededEvent first = seed(key, "codex", key + "-first", "Decision", "agent",
                "Window first " + key, "/tmp/" + key, null,
                Instant.parse("2026-07-01T12:00:00Z"));
        SeededEvent second = seed(key, "codex", key + "-second", "Decision", "agent",
                "Window second " + key, "/tmp/" + key, null,
                Instant.parse("2026-07-01T12:01:00Z"));
        seed(key, "codex", key + "-noise", "UserPromptSubmit", "user",
                "Window noise " + key, "/tmp/" + key, null,
                Instant.parse("2026-07-01T12:01:30Z"));
        seed(key, "codex", key + "-after", "Decision", "agent",
                "After window " + key, "/tmp/" + key, null,
                Instant.parse("2026-07-01T12:03:00Z"));
        String window = key + " since:2026-07-01T12:00:00Z until:2026-07-01T12:02:00Z";

        EventFeedResponse pageOne = repository.feed(window, true, null, null, 1);
        EventFeedResponse pageTwo = repository.feed(window, true, pageOne.nextBefore(), null, 2);

        assertThat(pageOne.items()).extracting(EventFeedItem::id).containsExactly(second.id());
        assertThat(pageOne.nextBefore()).isNotNull();
        assertThat(pageTwo.items()).extracting(EventFeedItem::id).containsExactly(first.id());
        assertThat(pageTwo.nextBefore()).isNull();
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
