package dev.nathan.sbaagentic.recording;

import dev.nathan.sbaagentic.recording.internal.adapter.out.sqlite.RecordingSqlStore;
import dev.nathan.sbaagentic.memory.MemoryEventReader;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.nathan.sbaagentic.project.ProjectAliasRequest;
import dev.nathan.sbaagentic.project.internal.application.ProjectAliasService;

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
    EventRecorder ingestService;

    @Autowired
    MemoryEventReader repository;

    @Autowired
    ProjectAliasService projectAliasService;

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
    void exactProjectFacetDoesNotLeakPrefixMatches() {
        String key = "exact-" + UUID.randomUUID().toString().replace("-", "");
        String source = "codex-" + key;
        String appId = seed(source, key + "-app", "Decision", "Exact search app " + key, "/tmp/" + key + "/app");
        String appOtherId = seed(source, key + "-app-other", "Decision", "Exact search app other " + key, "/tmp/" + key + "/app-other");

        List<String> ids = repository.searchEvents("source:" + source + " project_exact:/tmp/" + key + "/app", 50)
                .stream().map(AgentEvent::id).toList();

        assertThat(ids).containsExactly(appId).doesNotContain(appOtherId);
    }

    @Test
    void logicalProjectGroupFacetIncludesRegisteredAlias() {
        String key = "group-" + UUID.randomUUID().toString().replace("-", "");
        String source = "codex-" + key;
        String primary = "/tmp/" + key + "/primary";
        String alias = "/tmp/" + key + "/alias";
        String primaryId = seed(source, key + "-primary", "Decision", "Primary search event " + key, primary);
        String aliasId = seed(source, key + "-alias", "Decision", "Alias search event " + key, alias);
        projectAliasService.put(new ProjectAliasRequest(alias, primary));

        List<String> exactIds = repository.searchEvents(
                        "source:" + source + " project_exact:" + primary, 50)
                .stream().map(AgentEvent::id).toList();
        List<String> groupedIds = repository.searchEvents(
                        "source:" + source + " project_group:" + primary,
                        projectAliasService.scopesFor(primary),
                        50)
                .stream().map(AgentEvent::id).toList();

        assertThat(exactIds).containsExactly(primaryId).doesNotContain(aliasId);
        assertThat(groupedIds).containsExactlyInAnyOrder(aliasId, primaryId);
        projectAliasService.delete(alias);
    }

    @Test
    void plainQueryStillMatchesAcrossColumns() {
        String codexId = seedDecisionFromCodex();
        String claudeId = seedToolFromClaude();

        // No facet token: legacy substring behaviour across columns matches both by shared text.
        List<String> ids = repository.searchEvents("UI rewrite", 50).stream().map(AgentEvent::id).toList();
        assertThat(ids).contains(codexId, claudeId);
    }

    private String seed(String source, String clientSessionId, String eventType, String text, String cwd) {
        return ingestService.ingest(new EventIngestRequest(
                source, clientSessionId, "turn-" + clientSessionId, eventType, "assistant",
                text, cwd, null, null, null,
                Map.of("title", "Exact project " + clientSessionId),
                Instant.parse("2026-06-16T12:02:00Z"))).eventId();
    }
}
