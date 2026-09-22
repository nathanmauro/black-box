package dev.nathan.sbaagentic.memory.internal.application.port;

import dev.nathan.sbaagentic.memory.ElasticHealth;
import java.util.List;
import java.util.Map;

/** Optional, non-authoritative search mirror used by memory. */
public interface SearchIndex {

    ElasticHealth health();

    List<Map<String, Object>> search(String query, int limit);

    CompactResults searchCompact(String query, int limit);

    record CompactResults(String status, List<CompactEventReader.Candidate> items) {}

    List<String> termsEnum(String field, String prefix, int limit);

    List<Map<String, Object>> fieldCaps();
}
