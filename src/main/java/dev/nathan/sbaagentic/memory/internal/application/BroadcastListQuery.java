package dev.nathan.sbaagentic.memory.internal.application;

/**
 * Filters for listing broadcasts. Either {@code project} (a path or registered alias) or
 * {@code allProjects} is required. {@code staleOnly} keeps tentacles that are past their follow-up,
 * expired while still open, or untouched for {@code staleAfterHours} (default 72).
 * {@code includeCleared} adds cleared history and expired hazards and invariants.
 */
public record BroadcastListQuery(
        String project,
        String kind,
        Boolean staleOnly,
        Integer staleAfterHours,
        Boolean allProjects,
        Boolean includeCleared,
        Integer limit) {}
