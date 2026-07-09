package dev.nathan.sbaagentic.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class QueryFacetsTest {

    @Test
    void parsesFacetsAndFreeText() {
        QueryFacets f = QueryFacets.parse("source:codex kind:Decision rebase main");
        assertThat(f.source()).isEqualTo("codex");
        assertThat(f.eventType()).isEqualTo("Decision");
        assertThat(f.toolName()).isNull();
        assertThat(f.freeText()).containsExactly("rebase", "main");
        assertThat(f.hasAnyFacet()).isTrue();
    }

    @Test
    void quotedFreeTextStaysTogether() {
        QueryFacets f = QueryFacets.parse("tool:Edit \"ask history\"");
        assertThat(f.toolName()).isEqualTo("Edit");
        assertThat(f.freeText()).containsExactly("ask history");
        assertThat(f.freeTextPhrase()).isEqualTo("ask history");
    }

    @Test
    void quotedFacetValueStaysTogether() {
        QueryFacets f = QueryFacets.parse("project:\"sba agentic\" recall");
        assertThat(f.cwd()).isEqualTo("sba agentic");
        assertThat(f.freeText()).containsExactly("recall");
    }

    @Test
    void parsesInternalExactProjectFacet() {
        QueryFacets quoted = QueryFacets.parse("project_exact:\"/tmp/app\" kind:Decision");
        assertThat(quoted.exactCwd()).isEqualTo("/tmp/app");
        assertThat(quoted.eventType()).isEqualTo("Decision");
        assertThat(quoted.freeText()).isEmpty();
        assertThat(quoted.hasAnyFacet()).isTrue();

        QueryFacets noProject = QueryFacets.parse("project_exact:__no_project__");
        assertThat(noProject.exactCwd()).isEqualTo("__no_project__");
        assertThat(noProject.freeText()).isEmpty();
    }

    @Test
    void aliasesMapToColumns() {
        QueryFacets f = QueryFacets.parse("agent:claude event_type:Handoff tool_name:Bash cwd:proj");
        assertThat(f.source()).isEqualTo("claude");
        assertThat(f.eventType()).isEqualTo("Handoff");
        assertThat(f.toolName()).isEqualTo("Bash");
        assertThat(f.cwd()).isEqualTo("proj");
    }

    @Test
    void parsesReadableAndTerseNegativeFacets() {
        QueryFacets readable = QueryFacets.parse("source:codex NOT kind:PostToolUse recall");
        assertThat(readable.source()).isEqualTo("codex");
        assertThat(readable.excludedEventType()).isEqualTo("PostToolUse");
        assertThat(readable.freeText()).containsExactly("recall");
        assertThat(readable.hasAnyFacet()).isTrue();

        QueryFacets terse = QueryFacets.parse("-tool:Read -project:\"/tmp/sba agentic\"");
        assertThat(terse.excludedToolName()).isEqualTo("Read");
        assertThat(terse.excludedCwd()).isEqualTo("/tmp/sba agentic");
        assertThat(terse.freeText()).isEmpty();
    }

    @Test
    void danglingNotFallsBackToFreeText() {
        QueryFacets f = QueryFacets.parse("NOT recall bug");
        assertThat(f.hasAnyFacet()).isFalse();
        assertThat(f.freeText()).containsExactly("NOT", "recall", "bug");
    }

    @Test
    void lastFacetWins() {
        QueryFacets f = QueryFacets.parse("source:claude source:codex");
        assertThat(f.source()).isEqualTo("codex");
    }

    @Test
    void plainQueryIsAllFreeText() {
        QueryFacets f = QueryFacets.parse("recall bug");
        assertThat(f.hasAnyFacet()).isFalse();
        assertThat(f.freeText()).containsExactly("recall", "bug");
        assertThat(f.freeTextPhrase()).isEqualTo("recall bug");
    }

    @Test
    void nullAndBlankAreEmpty() {
        assertThat(QueryFacets.parse(null).hasAnyFacet()).isFalse();
        assertThat(QueryFacets.parse(null).freeText()).isEmpty();
        assertThat(QueryFacets.parse("   ").freeText()).isEmpty();
    }
}
