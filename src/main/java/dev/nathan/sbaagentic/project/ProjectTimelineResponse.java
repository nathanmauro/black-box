package dev.nathan.sbaagentic.project;

import java.util.List;

public record ProjectTimelineResponse(
        String projectKey,
        String canonicalKey,
        String label,
        int limit,
        int offset,
        long count,
        List<ProjectTimelineBlock> items) {
}
