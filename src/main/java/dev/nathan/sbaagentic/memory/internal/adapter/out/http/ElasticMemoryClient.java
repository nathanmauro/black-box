package dev.nathan.sbaagentic.memory.internal.adapter.out.http;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.nathan.sbaagentic.memory.ElasticsearchProperties;
import dev.nathan.sbaagentic.memory.MemoryHit;
import dev.nathan.sbaagentic.memory.MemoryRetrievalOperations;
import dev.nathan.sbaagentic.memory.MemoryRetrievalStatus;
import dev.nathan.sbaagentic.memory.MemoryRetrievalUnavailable;
import dev.nathan.sbaagentic.memory.MemoryRetrievalProperties;

import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class ElasticMemoryClient implements MemoryRetrievalOperations {

    private final ElasticsearchProperties elasticsearch;
    private final MemoryRetrievalProperties ask;
    private final RestClient restClient;

    public ElasticMemoryClient(
            ElasticsearchProperties elasticsearch,
            MemoryRetrievalProperties ask) {
        this.elasticsearch = elasticsearch;
        this.ask = ask;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(this.elasticsearch.getTimeout());
        requestFactory.setReadTimeout(this.elasticsearch.getTimeout());
        this.restClient = RestClient.builder()
                .baseUrl(this.elasticsearch.getBaseUrl())
                .requestFactory(requestFactory)
                .build();
    }

    @Override
    public MemoryRetrievalStatus status() {
        if (!elasticsearch.isEnabled()) {
            return MemoryRetrievalStatus.disabled("elasticsearch disabled");
        }
        try {
            // HEAD the memory index itself: a reachable cluster without the index is still an
            // unavailable ASK dependency, and the UI hides the workspace off this signal.
            restClient.head().uri("/{index}", ask.getMemoryIndex()).retrieve().toBodilessEntity();
            return MemoryRetrievalStatus.available(ask.getMemoryIndex());
        }
        catch (RestClientException ex) {
            return MemoryRetrievalStatus.unavailable(ex.getMessage());
        }
    }

    @Override
    public List<MemoryHit> bm25(String query, int limit) {
        if (!elasticsearch.isEnabled()) {
            throw new MemoryRetrievalUnavailable("elasticsearch disabled");
        }
        return search(bm25Query(query, limit, ask));
    }

    @Override
    public List<MemoryHit> knn(float[] embedding, int limit) {
        if (!elasticsearch.isEnabled()) {
            throw new MemoryRetrievalUnavailable("elasticsearch disabled");
        }
        return search(knnQuery(embedding, limit, ask));
    }

    static Map<String, Object> bm25Query(String query, int limit, MemoryRetrievalProperties ask) {
        Map<String, Object> multiMatch = new LinkedHashMap<>();
        multiMatch.put("query", query);
        multiMatch.put("fields", ask.getSearchFields());
        multiMatch.put("type", "best_fields");
        multiMatch.put("operator", "or");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("size", limit);
        body.put("query", Map.of("multi_match", multiMatch));
        body.put("highlight", Map.of(
                "pre_tags", List.of("<mark>"),
                "post_tags", List.of("</mark>"),
                "fields", Map.of(
                        "title", Map.of("number_of_fragments", 0),
                        "text", Map.of("fragment_size", 260, "number_of_fragments", 2),
                        "content", Map.of("fragment_size", 260, "number_of_fragments", 2),
                        "summary", Map.of("fragment_size", 260, "number_of_fragments", 2))));
        return body;
    }

    static Map<String, Object> knnQuery(float[] embedding, int limit, MemoryRetrievalProperties ask) {
        Map<String, Object> knn = new LinkedHashMap<>();
        knn.put("field", ask.getVectorField());
        knn.put("query_vector", embedding);
        knn.put("k", limit);
        knn.put("num_candidates", Math.max(50, limit * 5));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("size", limit);
        body.put("knn", knn);
        return body;
    }

    private List<MemoryHit> search(Map<String, Object> body) {
        try {
            Map<?, ?> response = restClient.post()
                    .uri("/{index}/_search", ask.getMemoryIndex())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(Map.class);
            return mapHits(response);
        }
        catch (RestClientException ex) {
            throw new MemoryRetrievalUnavailable(ex.getMessage());
        }
    }

    static List<MemoryHit> mapHits(Map<?, ?> response) {
        Object hitsObject = response == null ? null : response.get("hits");
        if (!(hitsObject instanceof Map<?, ?> hits)) {
            return List.of();
        }
        Object hitList = hits.get("hits");
        if (!(hitList instanceof List<?> list)) {
            return List.of();
        }
        List<MemoryHit> mapped = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> hit) {
                mapped.add(mapHit(hit));
            }
        }
        return mapped;
    }

    private static MemoryHit mapHit(Map<?, ?> hit) {
        Map<?, ?> source = hit.get("_source") instanceof Map<?, ?> map ? map : Map.of();
        String text = firstString(source, "text", "content", "summary", "chunk", "body");
        return new MemoryHit(
                string(hit.get("_id")),
                number(hit.get("_score")),
                firstString(source, "title", "session_title", "name"),
                firstString(source, "source", "agent", "kind", "corpus"),
                firstString(source, "source_path", "path", "file", "sourcePath"),
                firstString(source, "session_id", "sessionId", "black_box_session_id"),
                firstString(source, "client_session_id", "clientSessionId", "external_id"),
                firstString(source, "observed_at", "observedAt", "timestamp", "created_at", "createdAt", "ts"),
                text,
                snippet(hit, text));
    }

    private static String snippet(Map<?, ?> hit, String fallback) {
        Object highlightObject = hit.get("highlight");
        if (highlightObject instanceof Map<?, ?> highlight) {
            for (String field : List.of("text", "content", "summary", "title")) {
                Object fragments = highlight.get(field);
                if (fragments instanceof List<?> list && !list.isEmpty()) {
                    return clamp(String.join(" ... ", list.stream().map(String::valueOf).toList()), 520);
                }
            }
        }
        return clamp(fallback, 520);
    }

    private static String firstString(Map<?, ?> map, String... keys) {
        for (String key : keys) {
            Object value = map.get(key);
            if (value instanceof String text && !text.isBlank()) {
                return text;
            }
        }
        return "";
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static double number(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0.0;
    }

    private static String clamp(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }
}
