package dev.nathan.sbaagentic.workflow;

import java.time.Instant;

public record SessionLinkView(
        String linkId,
        String parentSessionId,
        String childSessionId,
        LinkType linkType,
        String taskId,
        Instant createdAt,
        SessionRef session) {
}
