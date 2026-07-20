package dev.nathan.sbaagentic.memory;

public record ElasticHealth(boolean enabled, boolean available, String indexName, String detail) {
}
