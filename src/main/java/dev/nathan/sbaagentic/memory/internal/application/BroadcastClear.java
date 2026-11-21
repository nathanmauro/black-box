package dev.nathan.sbaagentic.memory.internal.application;

/** A request to subtract one broadcast from a project's live set. */
public record BroadcastClear(String source, String clientSessionId, String repo, String key, String reason) {}
