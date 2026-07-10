package dev.nathan.sbaagentic.search;

import java.util.List;
import java.util.Map;

import dev.nathan.sbaagentic.event.AgentEvent;
import dev.nathan.sbaagentic.event.EventRepository;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class SearchServiceTest {

    @Test
    void suppressesElasticsearchForExactProjectScopeAndNegativeFacets() {
        EventRepository repository = mock(EventRepository.class);
        ElasticIndexClient elastic = mock(ElasticIndexClient.class);
        when(repository.searchEvents(anyString(), anyInt())).thenReturn(List.<AgentEvent>of());
        when(elastic.health()).thenReturn(new ElasticHealth(true, true, "sba-agentic-events", "reachable"));
        SearchService service = new SearchService(repository, elastic);

        SearchResponse exact = service.search("kind:Decision project_exact:\"/Users/nathan/Developer/proj/sba-agentic\"", 25);
        SearchResponse negative = service.search("NOT kind:PostToolUse project:sba-agentic", 25);

        assertThat(exact.elastic()).isEmpty();
        assertThat(negative.elastic()).isEmpty();
        verify(repository).searchEvents("kind:Decision project_exact:\"/Users/nathan/Developer/proj/sba-agentic\"", 25);
        verify(repository).searchEvents("NOT kind:PostToolUse project:sba-agentic", 25);
        verify(elastic, times(2)).health();
        verifyNoMoreInteractions(elastic);
    }

    @Test
    void keepsElasticsearchForPlainSearchQueries() {
        EventRepository repository = mock(EventRepository.class);
        ElasticIndexClient elastic = mock(ElasticIndexClient.class);
        when(repository.searchEvents(anyString(), anyInt())).thenReturn(List.<AgentEvent>of());
        when(elastic.search("recall bug", 10)).thenReturn(List.of(Map.of("id", "event-1")));
        when(elastic.health()).thenReturn(new ElasticHealth(true, true, "sba-agentic-events", "reachable"));
        SearchService service = new SearchService(repository, elastic);

        SearchResponse response = service.search("recall bug", 10);

        assertThat(response.elastic()).containsExactly(Map.of("id", "event-1"));
        verify(repository).searchEvents("recall bug", 10);
        verify(elastic).search("recall bug", 10);
        verify(elastic).health();
        verifyNoMoreInteractions(elastic);
    }
}
