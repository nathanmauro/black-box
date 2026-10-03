package dev.nathan.sbaagentic.memory.internal.application;

/** Filters for the Evidence listing. */
public record EvidenceListQuery(String target, String project, String repo, String q, Integer limit) {}
