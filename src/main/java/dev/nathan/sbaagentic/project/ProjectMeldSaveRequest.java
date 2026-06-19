package dev.nathan.sbaagentic.project;

import java.util.List;
import java.util.Map;

public record ProjectMeldSaveRequest(
        String projectKey,
        String title,
        String body,
        String provider,
        String model,
        String promptVersion,
        String executionMode,
        Boolean savedFromPreview,
        List<String> sessionIds,
        Map<String, Object> metadata) {
}
