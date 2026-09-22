package dev.nathan.sbaagentic.platform.internal.application;

import java.time.Instant;

public record StreamEventSnapshot(
        String id,
        String sessionId,
        String source,
        String eventType,
        String role,
        String text,
        String toolName,
        String title,
        Instant observedAt,
        String cwd,
        String spawnedBy) {}
