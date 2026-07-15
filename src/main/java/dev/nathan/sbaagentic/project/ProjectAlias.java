package dev.nathan.sbaagentic.project;

import java.time.Instant;

public record ProjectAlias(
        String id,
        String aliasKey,
        String canonicalKey,
        String source,
        Instant createdAt) {
}
