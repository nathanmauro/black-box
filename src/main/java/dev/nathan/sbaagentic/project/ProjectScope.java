package dev.nathan.sbaagentic.project;

public record ProjectScope(
        String projectKey,
        String canonicalKey,
        String label,
        boolean primary,
        String source) {
}
