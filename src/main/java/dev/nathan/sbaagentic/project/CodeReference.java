package dev.nathan.sbaagentic.project;

public record CodeReference(
        String projectKey,
        String relativePath,
        Integer line,
        Integer column,
        String commit) {
}
