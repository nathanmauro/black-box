package dev.nathan.sbaagentic.memory;

public interface CompactSearchOperations {
    CompactSearchResult search(
            String query, Integer limit, Integer maxBytes, String excludeSession, Boolean groupSimilar);
}
