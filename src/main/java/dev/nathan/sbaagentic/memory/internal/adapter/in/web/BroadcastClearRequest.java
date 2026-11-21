package dev.nathan.sbaagentic.memory.internal.adapter.in.web;

import dev.nathan.sbaagentic.memory.internal.application.BroadcastClear;

/** Body for {@code POST /api/broadcasts/clear}. */
public record BroadcastClearRequest(String source, String clientSessionId, String repo, String key, String reason) {

    BroadcastClear toCommand() {

        return new BroadcastClear(source, clientSessionId, repo, key, reason);
    }
}
