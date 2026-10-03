package dev.nathan.sbaagentic.project;

public interface BraidDiscoveryOperations {
    BraidDiscoveryResult find(
            String id, String query, String sessionId, Integer limit, String before, Integer maxBytes);
}
