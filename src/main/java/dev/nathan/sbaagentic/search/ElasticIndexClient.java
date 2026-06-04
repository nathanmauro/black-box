package dev.nathan.sbaagentic.search;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import dev.nathan.sbaagentic.config.SbaProperties;
import dev.nathan.sbaagentic.event.AgentEvent;
import dev.nathan.sbaagentic.session.AgentSession;

import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class ElasticIndexClient implements EventIndexSink {

    private static final List<String> SEARCH_FIELDS =
            List.of("text^4", "title^3", "cwd^2", "toolName", "eventType", "source", "clientSessionId");

    private final SbaProperties.Elasticsearch properties;
    private final RestClient restClient;
    private final AtomicBoolean indexReady = new AtomicBoolean();

    public ElasticIndexClient(SbaProperties properties) {
        this.properties = properties.getElasticsearch();
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(this.properties.getTimeout());
        requestFactory.setReadTimeout(this.properties.getTimeout());
        this.restClient = RestClient.builder()
                .baseUrl(this.properties.getBaseUrl())
                .requestFactory(requestFactory)
                .build();
    }

    @Override
    public boolean index(AgentSession session, AgentEvent event) {
        if (!properties.isEnabled()) {
            return false;
        }
        try {
            ensureIndex();
            restClient.put()
                    .uri("/{index}/_doc/{id}", properties.getIndexName(), event.id())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(document(session, event))
                    .retrieve()
                    .toBodilessEntity();
            return true;
        }
        catch (RestClientException ex) {
            return false;
        }
    }

    public ElasticHealth health() {
        if (!properties.isEnabled()) {
            return new ElasticHealth(false, false, properties.getIndexName(), "disabled");
        }
        try {
            restClient.get().uri("/").retrieve().toBodilessEntity();
            return new ElasticHealth(true, true, properties.getIndexName(), "reachable");
        }
        catch (RestClientException ex) {
            return new ElasticHealth(true, false, properties.getIndexName(), ex.getMessage());
        }
    }

    public List<Map<String, Object>> search(String query, int limit) {
        if (!properties.isEnabled() || query == null || query.isBlank()) {
            return List.of();
        }
        try {
            Map<String, Object> body = Map.of(
                    "size", limit,
                    "query", Map.of(
                            "bool", Map.of(
                                    "minimum_should_match", 1,
                                    "should", List.of(
                                            Map.of("multi_match", Map.of(
                                                    "query", query,
                                                    "fields", SEARCH_FIELDS,
                                                    "type", "phrase",
                                                    "boost", 2.0)),
                                            Map.of("multi_match", Map.of(
                                                    "query", query,
                                                    "fields", SEARCH_FIELDS,
                                                    "type", "best_fields",
                                                    "operator", "or",
                                                    "fuzziness", "AUTO",
                                                    "prefix_length", 1,
                                                    "max_expansions", 50))))),
                    "highlight", Map.of(
                            "pre_tags", List.of("<mark>"),
                            "post_tags", List.of("</mark>"),
                            "fields", Map.of(
                                    "title", Map.of("number_of_fragments", 0),
                                    "text", Map.of("fragment_size", 220, "number_of_fragments", 2))));

            Map<?, ?> response = restClient.post()
                    .uri("/{index}/_search", properties.getIndexName())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(Map.class);

            Object hitsObject = response == null ? null : response.get("hits");
            if (!(hitsObject instanceof Map<?, ?> hits)) {
                return List.of();
            }
            Object hitList = hits.get("hits");
            if (!(hitList instanceof List<?> list)) {
                return List.of();
            }
            return list.stream()
                    .filter(Map.class::isInstance)
                    .map(Map.class::cast)
                    .map(ElasticIndexClient::mapSearchHit)
                    .toList();
        }
        catch (RestClientException ex) {
            return List.of();
        }
    }

    private static Map<String, Object> mapSearchHit(Map<?, ?> hit) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", String.valueOf(hit.get("_id")));
        result.put("score", hit.get("_score") == null ? 0 : hit.get("_score"));
        result.put("source", hit.get("_source") == null ? Map.of() : hit.get("_source"));
        if (hit.get("highlight") != null) {
            result.put("highlight", hit.get("highlight"));
        }
        return result;
    }

    private void ensureIndex() {
        if (indexReady.get()) {
            return;
        }
        try {
            restClient.head().uri("/{index}", properties.getIndexName()).retrieve().toBodilessEntity();
            indexReady.set(true);
            return;
        }
        catch (RestClientException ignored) {
            // Create below. If another process wins the race, indexing will retry on the next event.
        }

        Map<String, Object> mapping = Map.of(
                "mappings", Map.of(
                        "properties", Map.of(
                                "sessionId", Map.of("type", "keyword"),
                                "source", Map.of("type", "keyword"),
                                "clientSessionId", Map.of("type", "keyword"),
                                "eventType", Map.of("type", "keyword"),
                                "turnId", Map.of("type", "keyword"),
                                "title", Map.of("type", "text"),
                                "cwd", Map.of("type", "keyword"),
                                "toolName", Map.of("type", "keyword"),
                                "text", Map.of("type", "text"),
                                "observedAt", Map.of("type", "date"))));
        restClient.put()
                .uri("/{index}", properties.getIndexName())
                .contentType(MediaType.APPLICATION_JSON)
                .body(mapping)
                .retrieve()
                .toBodilessEntity();
        indexReady.set(true);
    }

    private static Map<String, Object> document(AgentSession session, AgentEvent event) {
        return Map.of(
                "sessionId", session.id(),
                "source", event.source(),
                "clientSessionId", event.clientSessionId(),
                "eventType", event.eventType(),
                "turnId", event.turnId() == null ? "" : event.turnId(),
                "title", session.title(),
                "cwd", session.cwd() == null ? "" : session.cwd(),
                "toolName", event.toolName() == null ? "" : event.toolName(),
                "text", event.text() == null ? "" : event.text(),
                "observedAt", event.observedAt().toString());
    }
}
