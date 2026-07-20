package dev.nathan.sbaagentic.runner.internal.client.blackbox;

import java.time.Instant;

public record SessionLink(
        String id,
        String parentSessionId,
        String childSessionId,
        LinkType linkType,
        String taskId,
        Instant createdAt) {
}
