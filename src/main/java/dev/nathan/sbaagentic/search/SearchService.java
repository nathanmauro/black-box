package dev.nathan.sbaagentic.search;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import dev.nathan.sbaagentic.event.EventRepository;

import org.springframework.stereotype.Service;

@Service
public class SearchService {

    /**
     * Curated field list served when Elasticsearch is off/unreachable so the query bar always has
     * something to autocomplete. Mirrors the {@code keyword} fields mapped in
     * {@link ElasticIndexClient#ensureIndex()} ({@code source, eventType, toolName, cwd,
     * clientSessionId, sessionId, turnId}) plus the analyzed {@code text} fields ({@code title},
     * {@code text}), which are searchable but not aggregatable/enumerable (so value autocomplete on
     * them yields nothing — matching {@code _terms_enum}'s behavior on text fields).
     */
    private static final List<FieldInfo> CURATED_FIELDS = List.of(
            keyword("source"),
            keyword("eventType"),
            keyword("toolName"),
            keyword("cwd"),
            keyword("clientSessionId"),
            keyword("sessionId"),
            keyword("turnId"),
            text("title"),
            text("text"));

    /**
     * Soft refresh window: once a cache entry is this old, the next caller triggers a single-flight
     * async reload while still being served the (stale) cached value. Set to roughly half the hard
     * TTL, the conventional Caffeine refresh-ahead ratio.
     */
    private static final long SOFT_REFRESH_MILLIS = 5 * 60 * 1000L;

    /**
     * Hard TTL: once a cache entry is this old it is treated as fully expired and the next caller
     * blocks to reload. TTL is the chosen invalidation strategy because Elasticsearch exposes no
     * field-caps change token; mapping changes are rare, so a 10-minute eventual-consistency window
     * is acceptable. Index refresh is deliberately NOT used as a trigger (it fires ~every second and
     * does not signal a mapping change).
     */
    private static final long HARD_TTL_MILLIS = 10 * 60 * 1000L;

    private final EventRepository repository;
    private final ElasticIndexClient elasticIndexClient;

    /**
     * Single-entry, time-checked field-list cache. {@code holder} is the only mutable state and is
     * {@code volatile} so a value published by the loading thread is visible to all readers without
     * locking. {@code refreshing} is the single-flight latch that lets exactly one thread run the
     * (potentially slow) {@link ElasticIndexClient#fieldCaps()} call at a time, so concurrent callers
     * never stampede Elasticsearch.
     */
    private volatile CacheEntry holder;
    private final AtomicBoolean refreshing = new AtomicBoolean();

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

    /**
     * Query-bar autocomplete field list as a list of plain maps
     * ({@code {name,type,searchable,aggregatable}}) for JSON serialization. Backed by the cached
     * {@link #fieldInfos()} view: live from Elasticsearch {@code _field_caps} when reachable,
     * otherwise the curated static list.
     */
    public List<Map<String, Object>> fields() {
        List<FieldInfo> infos = fieldInfos();
        List<Map<String, Object>> result = new ArrayList<>(infos.size());
        for (FieldInfo info : infos) {
            result.add(info.toMap());
        }
        return result;
    }

    /**
     * Cached field list as typed {@link FieldInfo} records: live Elasticsearch {@code _field_caps}
     * when reachable, else the curated fallback mirroring the ES mapping so autocomplete works with
     * ES off. The list is held in a single-entry in-memory cache with serve-stale-on-soft-refresh
     * semantics and a hard TTL; concurrent callers are single-flighted so they never stampede the
     * {@code _field_caps} call.
     */
    public List<FieldInfo> fieldInfos() {
        CacheEntry current = holder;
        long now = System.currentTimeMillis();

        if (current == null || now - current.loadedAt >= HARD_TTL_MILLIS) {
            // Cold start or hard-expired: block and reload (single-flight; losers fall through and
            // serve whatever is published, or compute once more rather than returning null).
            return loadBlocking(current);
        }
        if (now - current.loadedAt >= SOFT_REFRESH_MILLIS) {
            // Soft-expired: kick a single async reload and serve the stale value immediately.
            triggerAsyncRefresh();
        }
        return current.fields;
    }

    /**
     * Values for a field, preferring Elasticsearch {@code _terms_enum} (snappy on large indexes) and
     * falling back to the SQLite {@code DISTINCT … LIKE 'prefix%'} path when ES returns empty/off.
     * Analyzed free-text fields ({@code text}, {@code title}) are not enumerable and return empty via
     * both paths. The limit is clamped to {@code [1,50]}.
     */
    public List<String> fieldValues(String field, String prefix, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 50));
        if (field == null || field.isBlank()) {
            return List.of();
        }
        List<String> fromElastic = elasticIndexClient.termsEnum(field, prefix, safeLimit);
        if (!fromElastic.isEmpty()) {
            return fromElastic;
        }
        // The repository accepts the UI (camelCase) field name and returns empty for text/unknown.
        return repository.distinctFieldValues(field, prefix, safeLimit);
    }

    /**
     * Backward-compatible alias for {@link #fieldValues(String, String, int)} retained for existing
     * callers (the {@code /search/values} endpoint and CLI).
     */
    public List<String> values(String field, String prefix, int limit) {
        return fieldValues(field, prefix, limit);
    }

    /** Cold/hard-expired load: refresh exactly once, falling back to stale/curated on contention. */
    private List<FieldInfo> loadBlocking(CacheEntry stale) {
        if (refreshing.compareAndSet(false, true)) {
            try {
                List<FieldInfo> loaded = loadFields();
                holder = new CacheEntry(loaded, System.currentTimeMillis());
                return loaded;
            }
            finally {
                refreshing.set(false);
            }
        }
        // Another thread is loading. Serve whatever is currently published; if nothing is yet (true
        // cold start under a race), return the curated list so callers never see null/empty.
        CacheEntry published = holder;
        if (published != null) {
            return published.fields;
        }
        return stale != null ? stale.fields : CURATED_FIELDS;
    }

    /** Soft-refresh: one async, single-flighted reload; the stale value keeps being served. */
    private void triggerAsyncRefresh() {
        if (!refreshing.compareAndSet(false, true)) {
            return;
        }
        Runnable reload = () -> {
            try {
                List<FieldInfo> loaded = loadFields();
                holder = new CacheEntry(loaded, System.currentTimeMillis());
            }
            finally {
                refreshing.set(false);
            }
        };
        try {
            // Use the common pool to avoid blocking the calling (request) thread, mirroring
            // Caffeine's refreshAfterWrite, which reloads on the ForkJoinPool.
            java.util.concurrent.ForkJoinPool.commonPool().execute(reload);
        }
        catch (RuntimeException ex) {
            // If the pool rejects the task, reset the latch so a later caller can retry.
            refreshing.set(false);
        }
    }

    /** Source of truth for one (re)load: live {@code _field_caps} when non-empty, else curated. */
    private List<FieldInfo> loadFields() {
        List<Map<String, Object>> live = elasticIndexClient.fieldCaps();
        if (live == null || live.isEmpty()) {
            return CURATED_FIELDS;
        }
        List<FieldInfo> result = new ArrayList<>(live.size());
        for (Map<String, Object> entry : live) {
            result.add(FieldInfo.fromMap(entry));
        }
        return result;
    }

    private static FieldInfo keyword(String name) {
        return new FieldInfo(name, "keyword", true, true);
    }

    private static FieldInfo text(String name) {
        return new FieldInfo(name, "text", true, false);
    }

    /** One cached snapshot of the field list plus the wall-clock time it was loaded. */
    private record CacheEntry(List<FieldInfo> fields, long loadedAt) {
    }

    /**
     * One query-bar field: its UI {@code name}, ES {@code type}, and whether it is {@code searchable}
     * / {@code aggregatable}. Keyword-family fields are aggregatable (and value-enumerable); analyzed
     * {@code text} fields are searchable but not aggregatable.
     */
    public record FieldInfo(String name, String type, boolean searchable, boolean aggregatable) {

        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("name", name);
            map.put("type", type);
            map.put("searchable", searchable);
            map.put("aggregatable", aggregatable);
            return map;
        }

        static FieldInfo fromMap(Map<String, Object> map) {
            Object name = map.get("name");
            Object type = map.get("type");
            return new FieldInfo(
                    name == null ? "" : String.valueOf(name),
                    type == null ? "text" : String.valueOf(type),
                    Boolean.TRUE.equals(map.get("searchable")),
                    Boolean.TRUE.equals(map.get("aggregatable")));
        }
    }
}
