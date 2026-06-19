package dev.nathan.sbaagentic.project;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record ProjectSavedMeld(
        String id,
        String projectKey,
        String canonicalKey,
        String title,
        String body,
        String provider,
        String model,
        String promptVersion,
        String executionMode,
        boolean savedFromPreview,
        Map<String, Object> metadata,
        Instant createdAt,
        List<ProjectMeldSessionRef> sessions) {
}
