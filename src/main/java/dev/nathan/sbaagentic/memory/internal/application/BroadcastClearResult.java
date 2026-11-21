package dev.nathan.sbaagentic.memory.internal.application;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.nathan.sbaagentic.memory.ProjectBroadcast;

/**
 * The outcome of a clear. {@code cleared} is true only when this call closed an open broadcast;
 * repeating a clear is harmless and returns the most recently cleared row, or none if the key never
 * existed.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BroadcastClearResult(String projectScope, String key, boolean cleared, ProjectBroadcast broadcast) {}
