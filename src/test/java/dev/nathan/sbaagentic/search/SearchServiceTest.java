package dev.nathan.sbaagentic.search;

import java.util.List;
import java.util.Map;

import dev.nathan.sbaagentic.recording.AgentEvent;
import dev.nathan.sbaagentic.memory.MemoryEventReader;
import dev.nathan.sbaagentic.memory.ElasticHealth;
import dev.nathan.sbaagentic.memory.SearchResponse;
import dev.nathan.sbaagentic.memory.internal.application.SearchService;
import dev.nathan.sbaagentic.memory.internal.application.port.SearchIndex;
import dev.nathan.sbaagentic.project.ProjectScopeOperations;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class SearchServiceTest {

    @Test
    void suppressesElasticsearchForExactGroupedProjectScopeAndNegativeFacets() {
        MemoryEventReader repository = mock(MemoryEventReader.class);
        SearchIndex elastic = mock(SearchIndex.class);
        ProjectScopeOperations projectScopes = mock(ProjectScopeOperations.class);
        when(projectScopes.scopesFor(anyString())).thenReturn(List.of("/project"));
        when(repository.searchEvents(anyString(), anyList(), anyInt())).thenReturn(List.<AgentEvent>of());
        when(elastic.health()).thenReturn(new ElasticHealth(true, true, "sba-agentic-events", "reachable"));
        SearchService service = new SearchService(repository, elastic, projectScopes);

        SearchResponse exact = service.search("kind:Decision project_exact:\"/Users/nathan/Developer/proj/sba-agentic\"", 25);
        SearchResponse grouped = service.search("project_group:\"/Users/nathan/Developer/proj/sba-agentic\"", 25);
        SearchResponse negative = service.search("NOT kind:PostToolUse project:sba-agentic", 25);

        assertThat(exact.elastic()).isEmpty();
        assertThat(grouped.elastic()).isEmpty();
        assertThat(negative.elastic()).isEmpty();
        verify(repository).searchEvents(
                "kind:Decision project_exact:\"/Users/nathan/Developer/proj/sba-agentic\"", List.of(), 25);
        verify(repository).searchEvents(
                "project_group:\"/Users/nathan/Developer/proj/sba-agentic\"", List.of("/project"), 25);
        verify(repository).searchEvents("NOT kind:PostToolUse project:sba-agentic", List.of(), 25);
        verify(elastic, times(3)).health();
        verifyNoMoreInteractions(elastic);
    }

    @Test
    void keepsElasticsearchForPlainSearchQueries() {
        MemoryEventReader repository = mock(MemoryEventReader.class);
        SearchIndex elastic = mock(SearchIndex.class);
        ProjectScopeOperations projectScopes = mock(ProjectScopeOperations.class);
        when(repository.searchEvents(anyString(), anyList(), anyInt())).thenReturn(List.<AgentEvent>of());
        when(elastic.search("recall bug", 10)).thenReturn(List.of(Map.of("id", "event-1")));
        when(elastic.health()).thenReturn(new ElasticHealth(true, true, "sba-agentic-events", "reachable"));
        SearchService service = new SearchService(repository, elastic, projectScopes);

        SearchResponse response = service.search("recall bug", 10);

        assertThat(response.elastic()).containsExactly(Map.of("id", "event-1"));
        verify(repository).searchEvents("recall bug", List.of(), 10);
        verify(elastic).search("recall bug", 10);
        verify(elastic).health();
        verifyNoMoreInteractions(elastic);
    }
}
