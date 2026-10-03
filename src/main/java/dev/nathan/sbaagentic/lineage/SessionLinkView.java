package dev.nathan.sbaagentic.lineage;

import java.time.Instant;

public record SessionLinkView(
        String linkId,
        String parentSessionId,
        String childSessionId,
        LinkType linkType,
        Instant createdAt,
        SessionRef session) {}
