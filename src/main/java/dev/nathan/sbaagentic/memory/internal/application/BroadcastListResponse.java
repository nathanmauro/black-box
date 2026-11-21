package dev.nathan.sbaagentic.memory.internal.application;

import dev.nathan.sbaagentic.memory.ProjectBroadcast;
import java.time.Instant;
import java.util.List;

/** A filtered page of broadcasts, evaluated at {@code asOf} with the given stale threshold. */
public record BroadcastListResponse(List<ProjectBroadcast> broadcasts, int count, int staleAfterHours, Instant asOf) {}
