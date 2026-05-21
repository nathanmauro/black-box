package dev.nathan.sbaagentic.search;

public record ElasticHealth(boolean enabled, boolean available, String indexName, String detail) {
}
