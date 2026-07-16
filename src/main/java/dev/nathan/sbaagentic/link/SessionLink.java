package dev.nathan.sbaagentic.link;

import java.time.Instant;

public record SessionLink(
        String id,
        String parentSessionId,
        String childSessionId,
        LinkType linkType,
        String taskId,
        Instant createdAt) {
}
