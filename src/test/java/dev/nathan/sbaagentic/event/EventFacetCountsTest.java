package dev.nathan.sbaagentic.recording;

import static org.assertj.core.api.Assertions.assertThat;

import dev.nathan.sbaagentic.recording.internal.adapter.out.sqlite.EventFtsIndex;
import dev.nathan.sbaagentic.recording.internal.adapter.out.sqlite.RecordingSqlStore;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Pins the query-scoped facet-counts contract (spec §6.5, D10): totals under the full predicate,
 * per-field counts with the field's own include list dropped ("what if I switched"), the shared
 * {@code is:all}-beats-{@code meaningful} precedence, and the degraded envelope while free text
 * cannot be counted through FTS.
 */
@SpringBootTest(
        properties = {
            // A temp file DB takes the production WAL + busy_timeout path; cache=shared
            // memory throws SQLITE_LOCKED on writer collisions, ignoring busy_timeout.
            "spring.datasource.url=jdbc:sqlite:${java.io.tmpdir}/bb-event-facet-counts-test-${random.uuid}.db",
            "sba.local-ai.enabled=false",
            "sba.summary.backend=local",
            "sba.elasticsearch.enabled=false",
            "sba.memory.embedding.enabled=false"
        })
class EventFacetCountsTest {

    @Autowired
    EventRecorder ingestService;

    @Autowired
    RecordingSqlStore repository;

    @Autowired
    EventFtsIndex ftsIndex;

    @Test
    void countsScopeToTheQueryAcrossAllFourFields() {
        String key = uniqueKey("scoped");
        seed(
                key,
                "codex-" + key,
                key + "-a",
                "Decision",
                "/tmp/" + key + "/alpha",
                null,
                Instant.parse("2026-07-01T12:00:00Z"));
        seed(
                key,
                "codex-" + key,
                key + "-a",
                "PostToolUse",
                "/tmp/" + key + "/alpha",
                "Read",
                Instant.parse("2026-07-01T12:01:00Z"));
        seed(
                key,
                "claude-" + key,
                key + "-b",
                "Handoff",
                "/tmp/" + key + "/beta",
                "Edit",
                Instant.parse("2026-07-01T12:02:00Z"));

        EventFacetCounts counts = repository.facetCounts(key, false);

        assertThat(counts.reason()).isNull();
        assertThat(counts.total()).isEqualTo(3);
        assertThat(counts.fields().source())
                .containsExactly(
                        new EventFacetCounts.ValueCount("codex-" + key, 2),
                        new EventFacetCounts.ValueCount("claude-" + key, 1));
        assertThat(counts.fields().kind())
                .extracting(EventFacetCounts.ValueCount::value)
                .containsExactlyInAnyOrder("Decision", "PostToolUse", "Handoff");
        // Rows without a tool_name never surface as a phantom tool value.
        assertThat(counts.fields().tool())
                .containsExactlyInAnyOrder(
                        new EventFacetCounts.ValueCount("Read", 1), new EventFacetCounts.ValueCount("Edit", 1));
        assertThat(counts.fields().project())
                .containsExactlyInAnyOrder(
                        new EventFacetCounts.ValueCount("/tmp/" + key + "/alpha", 2),
                        new EventFacetCounts.ValueCount("/tmp/" + key + "/beta", 1));
    }

    @Test
    void perFieldCountsDropTheFieldsOwnIncludeListButKeepEverythingElse() {
        String key = uniqueKey("dropown");
        seed(key, "codex-" + key, key + "-a", "Decision", "/tmp/" + key, null, Instant.parse("2026-07-01T12:00:00Z"));
        seed(key, "codex-" + key, key + "-a", "Handoff", "/tmp/" + key, null, Instant.parse("2026-07-01T12:01:00Z"));
        seed(
                key,
                "claude-" + key,
                key + "-b",
                "Observation",
                "/tmp/" + key,
                null,
                Instant.parse("2026-07-01T12:02:00Z"));

        EventFacetCounts counts = repository.facetCounts("source:codex-" + key + " " + key, false);

        // The total honors the full predicate; the source list answers "what if I switched".
        assertThat(counts.total()).isEqualTo(2);
        assertThat(counts.fields().source())
                .containsExactly(
                        new EventFacetCounts.ValueCount("codex-" + key, 2),
                        new EventFacetCounts.ValueCount("claude-" + key, 1));
        // Other fields keep the source filter: claude's Observation is not a kind option here.
        assertThat(counts.fields().kind())
                .extracting(EventFacetCounts.ValueCount::value)
                .containsExactlyInAnyOrder("Decision", "Handoff");
    }

    @Test
    void excludedValuesStayExcludedEvenInTheirOwnFieldList() {
        String key = uniqueKey("dropneg");
        seed(key, "codex-" + key, key + "-a", "Decision", "/tmp/" + key, null, Instant.parse("2026-07-01T12:00:00Z"));
        seed(key, "claude-" + key, key + "-b", "Decision", "/tmp/" + key, null, Instant.parse("2026-07-01T12:01:00Z"));

        EventFacetCounts counts = repository.facetCounts("-source:claude-" + key + " " + key, false);

        // Only include lists are dropped; a negation is intent, not a switchable choice.
        assertThat(counts.total()).isEqualTo(1);
        assertThat(counts.fields().source()).containsExactly(new EventFacetCounts.ValueCount("codex-" + key, 1));
    }

    @Test
    void emptyQueryCountsTheWholeCorpusWithPopulatedFields() {
        String key = uniqueKey("empty");
        seed(key, "codex-" + key, key + "-a", "Decision", "/tmp/" + key, null, Instant.parse("2026-07-01T12:00:00Z"));

        EventFacetCounts counts = repository.facetCounts(null, false);

        assertThat(counts.reason()).isNull();
        assertThat(counts.total()).isEqualTo(repository.stats().events());
        assertThat(counts.fields().source())
                .extracting(EventFacetCounts.ValueCount::value)
                .contains("codex-" + key);
    }

    @Test
    void isAllInQueryBeatsMeaningfulTrueExactlyLikeTheFeed() {
        String key = uniqueKey("isall");
        seed(key, "codex-" + key, key + "-a", "Decision", "/tmp/" + key, null, Instant.parse("2026-07-01T12:00:00Z"));
        seedUserPrompt(key, "codex-" + key, key + "-a", "/tmp/" + key, Instant.parse("2026-07-01T12:01:00Z"));

        assertThat(repository.facetCounts(key, true).total()).isEqualTo(1);
        assertThat(repository.facetCounts(key + " is:all", true).total()).isEqualTo(2);
    }

    @Test
    void freeTextWithoutReadyFtsSkipsCountsWithTheBackfillEnvelope() {
        String key = uniqueKey("skip");
        seed(key, "codex-" + key, key + "-a", "Decision", "/tmp/" + key, null, Instant.parse("2026-07-01T12:00:00Z"));

        assertThat(ftsIndex.ready()).isTrue();
        try {
            ftsIndex.markUnavailable();

            EventFacetCounts skipped = repository.facetCounts(key, false);
            assertThat(skipped.total()).isNull();
            assertThat(skipped.fields()).isNull();
            assertThat(skipped.reason()).isEqualTo("backfill");

            // Without free text there is nothing to scan: counts stay available on pure facets.
            EventFacetCounts facetsOnly = repository.facetCounts("source:codex-" + key, false);
            assertThat(facetsOnly.reason()).isNull();
            assertThat(facetsOnly.total()).isEqualTo(1);
        } finally {
            ftsIndex.ensureFtsSchema();
        }
    }

    @Test
    void freeTextCountsRideFtsAndScopeEveryField() {
        String key = uniqueKey("fts");
        String needle = "needleterm" + key;
        seed(
                key,
                "codex-" + key,
                key + "-a",
                "Decision",
                "/tmp/" + key,
                null,
                Instant.parse("2026-07-01T12:00:00Z"),
                "Contains " + needle + " here");
        seed(
                key,
                "codex-" + key,
                key + "-a",
                "Decision",
                "/tmp/" + key,
                null,
                Instant.parse("2026-07-01T12:01:00Z"),
                "Without the term");

        assertThat(ftsIndex.ready()).isTrue();
        EventFacetCounts counts = repository.facetCounts(needle, false);

        assertThat(counts.total()).isEqualTo(1);
        assertThat(counts.fields().source()).containsExactly(new EventFacetCounts.ValueCount("codex-" + key, 1));
    }

    private void seed(
            String key,
            String source,
            String clientSessionId,
            String eventType,
            String cwd,
            String toolName,
            Instant observedAt) {
        seed(key, source, clientSessionId, eventType, cwd, toolName, observedAt, "Facet count event " + key);
    }

    private void seed(
            String key,
            String source,
            String clientSessionId,
            String eventType,
            String cwd,
            String toolName,
            Instant observedAt,
            String text) {
        ingestService.ingest(new EventIngestRequest(
                source,
                clientSessionId,
                "turn-" + clientSessionId,
                eventType,
                "assistant",
                text,
                cwd,
                toolName,
                null,
                null,
                Map.of("title", "Title " + clientSessionId, "facetKey", key),
                observedAt));
    }

    /** A user prompt fails the meaningful predicate, unlike assistant-role events. */
    private void seedUserPrompt(String key, String source, String clientSessionId, String cwd, Instant observedAt) {
        ingestService.ingest(new EventIngestRequest(
                source,
                clientSessionId,
                "turn-" + clientSessionId,
                "UserPromptSubmit",
                "user",
                "Prompt noise " + key,
                cwd,
                null,
                null,
                null,
                Map.of("title", "Title " + clientSessionId, "facetKey", key),
                observedAt));
    }

    private static String uniqueKey(String prefix) {

        return prefix + "-" + UUID.randomUUID().toString().replace("-", "");
    }
}
