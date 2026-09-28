package dev.nathan.sbaagentic.memory;

import java.util.List;
import java.util.Map;

public interface MemorySearchOperations {

    default SearchResponse search(String query, int limit) {

        return search(query, limit, false);
    }

    /** {@code humanOnly} matches only the human's own turns and skips the Elasticsearch leg. */
    SearchResponse search(String query, int limit, boolean humanOnly);

    List<Map<String, Object>> fields();

    List<String> fieldValues(String field, String prefix, int limit);

    ElasticHealth elasticHealth();
}
