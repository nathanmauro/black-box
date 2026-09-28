package dev.nathan.sbaagentic.memory.internal.application;

import java.util.List;

/**
 * Filters for the Ideas view. {@code statuses} and {@code origin} use the capture vocabulary;
 * {@code project} and {@code repo} are equivalent project scopes resolved through project aliases;
 * {@code q} is whitespace-separated terms that must all appear in the idea's text fields.
 */
public record IdeaListQuery(
        List<String> statuses, String origin, String project, String repo, String q, Integer limit) {}
