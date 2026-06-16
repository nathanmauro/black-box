package dev.nathan.sbaagentic.project;

import java.util.List;

public record ProjectMeldPreviewResponse(
        String status,
        String executionMode,
        String provider,
        String model,
        String projectKey,
        String canonicalKey,
        String title,
        String preview,
        String bundle,
        List<ProjectMeldSessionRef> sessions,
        int sessionCount,
        int evidenceCount,
        int bundleChars,
        List<String> degradationNotes) {
}
