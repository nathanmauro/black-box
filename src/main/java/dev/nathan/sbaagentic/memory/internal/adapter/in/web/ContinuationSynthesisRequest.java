package dev.nathan.sbaagentic.memory.internal.adapter.in.web;

/** {@code POST /api/continuations/synthesize}: the same inputs as the GET, plus one candidate ID. */
public record ContinuationSynthesisRequest(String project, Integer withinHours, String candidateId) {}
