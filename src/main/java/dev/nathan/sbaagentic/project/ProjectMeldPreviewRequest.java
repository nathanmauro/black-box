package dev.nathan.sbaagentic.project;

import java.util.List;

public record ProjectMeldPreviewRequest(
        List<String> sessionIds,
        String provider,
        String model,
        String executionMode) {
}
