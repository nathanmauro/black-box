package dev.nathan.sbaagentic.memory;

import java.util.List;
import java.util.Map;

public interface MemorySearchOperations {

    SearchResponse search(String query, int limit);

    List<Map<String, Object>> fields();

    List<String> fieldValues(String field, String prefix, int limit);

    ElasticHealth elasticHealth();
}
