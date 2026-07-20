package dev.nathan.sbaagentic.memory.internal.application.port;

import java.util.List;
import java.util.Map;

import dev.nathan.sbaagentic.memory.ElasticHealth;

/** Optional, non-authoritative search mirror used by memory. */
public interface SearchIndex {

    ElasticHealth health();

    List<Map<String, Object>> search(String query, int limit);

    List<String> termsEnum(String field, String prefix, int limit);

    List<Map<String, Object>> fieldCaps();
}
