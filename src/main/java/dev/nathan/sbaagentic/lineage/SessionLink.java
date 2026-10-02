package dev.nathan.sbaagentic.lineage;

import java.time.Instant;

public record SessionLink(
        String id, String parentSessionId, String childSessionId, LinkType linkType, Instant createdAt) {}
