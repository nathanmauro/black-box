package dev.nathan.sbaagentic.event;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the SQLite search path honours {@code field:value} facets without Elasticsearch, while
 * leaving plain free-text search behaviour unchanged.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite:file:event-facet-test?mode=memory&cache=shared",
        "sba.local-ai.enabled=false",
        "sba.summary.backend=local",
        "sba.elasticsearch.enabled=false"
})
class EventSearchFacetTest {

    @Autowired
    EventIngestService ingestService;

    @Autowired
    EventRepository repository;

    private String seedDecisionFromCodex() {
        return ingestService.ingest(new EventIngestRequest(
                "codex", "facet-codex", "turn-1", "Decision", "assistant",
                "Use SolidJS for the UI rewrite.", "/tmp/sba-agentic", null, null, null,
                Map.of("title", "Stack decision"),
                Instant.parse("2026-06-16T12:00:00Z"))).eventId();
    }

    private String seedToolFromClaude() {
        return ingestService.ingest(new EventIngestRequest(
                "claude", "facet-claude", "turn-1", "PostToolUse", "assistant",
                "Edited app.js for the UI rewrite.", "/tmp/sba-agentic", "Edit", null, null,
                Map.of("title", "Edit app.js"),
                Instant.parse("2026-06-16T12:01:00Z"))).eventId();
    }

    @Test
    void sourceFacetFiltersToOneAgent() {
        String codexId = seedDecisionFromCodex();
        String claudeId = seedToolFromClaude();

        List<String> ids = repository.searchEvents("source:codex", 50).stream().map(AgentEvent::id).toList();
        assertThat(ids).contains(codexId).doesNotContain(claudeId);
    }

    @Test
    void kindFacetFiltersToEventType() {
        String codexId = seedDecisionFromCodex();
        String claudeId = seedToolFromClaude();

        List<String> ids = repository.searchEvents("kind:Decision", 50).stream().map(AgentEvent::id).toList();
        assertThat(ids).contains(codexId).doesNotContain(claudeId);
    }

    @Test
    void facetPlusFreeTextNarrowsFurther() {
        seedDecisionFromCodex();
        String claudeId = seedToolFromClaude();

        // tool:Edit narrows to the PostToolUse event; "rewrite" free text still matches its text.
        List<String> ids = repository.searchEvents("tool:Edit rewrite", 50).stream().map(AgentEvent::id).toList();
        assertThat(ids).containsExactly(claudeId);
    }

    @Test
    void negativeFacetsExcludeMatchingRows() {
        String codexId = seedDecisionFromCodex();
        String claudeId = seedToolFromClaude();

        List<String> noToolNoise = repository.searchEvents("NOT kind:PostToolUse UI rewrite", 50)
                .stream().map(AgentEvent::id).toList();
        assertThat(noToolNoise).contains(codexId).doesNotContain(claudeId);

        List<String> noCodex = repository.searchEvents("-source:codex rewrite", 50)
                .stream().map(AgentEvent::id).toList();
        assertThat(noCodex).contains(claudeId).doesNotContain(codexId);
    }

    @Test
    void plainQueryStillMatchesAcrossColumns() {
        String codexId = seedDecisionFromCodex();
        String claudeId = seedToolFromClaude();

        // No facet token: legacy substring behaviour across columns matches both by shared text.
        List<String> ids = repository.searchEvents("UI rewrite", 50).stream().map(AgentEvent::id).toList();
        assertThat(ids).contains(codexId, claudeId);
    }
}
