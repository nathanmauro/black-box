package dev.nathan.sbaagentic.search;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import dev.nathan.sbaagentic.config.SbaProperties;
import dev.nathan.sbaagentic.recording.AgentEvent;
import dev.nathan.sbaagentic.recording.AgentSession;

import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class ElasticIndexClient implements EventIndexSink {

    private static final List<String> SEARCH_FIELDS =
            List.of("text^4", "title^3", "cwd^2", "toolName", "eventType", "source", "clientSessionId");

    /**
     * Keyword-family fields actually mapped as {@code keyword} in {@link #ensureIndex()}. Only these
     * may be passed to {@code _terms_enum}; the analyzed {@code text} fields ({@code title},
     * {@code text}) are excluded because {@code _terms_enum} silently returns an empty array on a
     * text field (it supports only keyword/constant_keyword/flattened/version/ip).
     */
    private static final Set<String> TERMS_ENUM_FIELDS = Set.of(
            "sessionId", "source", "clientSessionId", "eventType", "turnId", "cwd", "toolName");

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

    /**
     * Field list for query-bar autocomplete, derived from Elasticsearch {@code _field_caps}. Each
     * entry is {@code {name,type,searchable,aggregatable}}. Internal {@code _}-prefixed fields are
     * skipped. Returns empty (signalling the caller to use the curated fallback) when ES is off or
     * unreachable.
     */
    public List<Map<String, Object>> fieldCaps() {
        if (!properties.isEnabled()) {
            return List.of();
        }
        try {
            Map<?, ?> response = restClient.get()
                    .uri("/{index}/_field_caps?fields=*", properties.getIndexName())
                    .retrieve()
                    .body(Map.class);
            Object fieldsObject = response == null ? null : response.get("fields");
            if (!(fieldsObject instanceof Map<?, ?> fields)) {
                return List.of();
            }
            List<Map<String, Object>> result = new java.util.ArrayList<>();
            for (Map.Entry<?, ?> entry : fields.entrySet()) {
                String name = String.valueOf(entry.getKey());
                if (name.startsWith("_")) {
                    continue;
                }
                if (!(entry.getValue() instanceof Map<?, ?> byType) || byType.isEmpty()) {
                    continue;
                }
                Object firstCapObject = byType.values().iterator().next();
                if (!(firstCapObject instanceof Map<?, ?> cap)) {
                    continue;
                }
                Object type = cap.get("type");
                Map<String, Object> field = new LinkedHashMap<>();
                field.put("name", name);
                field.put("type", type == null ? "text" : String.valueOf(type));
                field.put("searchable", Boolean.TRUE.equals(cap.get("searchable")));
                field.put("aggregatable", Boolean.TRUE.equals(cap.get("aggregatable")));
                result.add(field);
            }
            return result;
        }
        catch (RestClientException ex) {
            return List.of();
        }
    }

    /**
     * Low-latency prefix value lookup for query-bar autocomplete, backed by Elasticsearch
     * {@code _terms_enum} (the API that powers Kibana value suggestions). Delegates to
     * {@link #termsEnum(String, String, int, boolean)} with {@code caseInsensitive=false} (the ES
     * default).
     */
    public List<String> termsEnum(String field, String prefix, int size) {
        return termsEnum(field, prefix, size, false);
    }

    /**
     * Low-latency prefix value lookup for query-bar autocomplete, backed by Elasticsearch
     * {@code _terms_enum}: {@code POST /{index}/_terms_enum} with body
     * {@code {field, string:prefix, size, case_insensitive}}, reading the top-level {@code terms[]}
     * array from the JSON object response.
     *
     * <p>Callable fields are restricted to the keyword-family fields mapped in {@link #ensureIndex()}
     * ({@code sessionId, source, clientSessionId, eventType, turnId, cwd, toolName}); analyzed
     * {@code text} fields ({@code title}, {@code text}) are rejected with an empty list because
     * {@code _terms_enum} returns {@code []} on text fields. Returns empty when ES is off, the field
     * is blank/unsupported, or on any {@link RestClientException} — the caller then falls back to the
     * SQLite {@code DISTINCT} path.
     *
     * <p>Note: {@code _terms_enum} requires Elasticsearch &gt;= 7.14.
     */
    public List<String> termsEnum(String field, String prefix, int size, boolean caseInsensitive) {
        if (!properties.isEnabled() || field == null || field.isBlank()
                || !TERMS_ENUM_FIELDS.contains(field)) {
            return List.of();
        }
        try {
            Map<String, Object> body = Map.of(
                    "field", field,
                    "string", prefix == null ? "" : prefix,
                    "size", size,
                    "case_insensitive", caseInsensitive);
            Map<?, ?> response = restClient.post()
                    .uri("/{index}/_terms_enum", properties.getIndexName())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(Map.class);
            Object terms = response == null ? null : response.get("terms");
            if (!(terms instanceof List<?> list)) {
                return List.of();
            }
            return list.stream().map(String::valueOf).toList();
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

        Map<String, Object> indexDefinition = Map.of(
                "settings", Map.of(
                        "number_of_shards", 1,
                        "number_of_replicas", properties.getNumberOfReplicas()),
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
                .body(indexDefinition)
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
