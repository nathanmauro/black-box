package dev.nathan.sbaagentic.search;

import dev.nathan.sbaagentic.event.EventRepository;

import org.springframework.stereotype.Service;

@Service
public class SearchService {

    private final EventRepository repository;
    private final ElasticIndexClient elasticIndexClient;

    public SearchService(EventRepository repository, ElasticIndexClient elasticIndexClient) {
        this.repository = repository;
        this.elasticIndexClient = elasticIndexClient;
    }

    public SearchResponse search(String query, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        return new SearchResponse(
                query,
                repository.searchEvents(query, safeLimit),
                elasticIndexClient.search(query, safeLimit),
                elasticIndexClient.health());
    }
}
