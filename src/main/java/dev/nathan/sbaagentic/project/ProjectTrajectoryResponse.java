package dev.nathan.sbaagentic.project;

import java.time.Instant;
import java.util.List;

public record ProjectTrajectoryResponse(
        String projectKey,
        String canonicalKey,
        String label,
        Instant generatedAt,
        long totalCaptures,
        List<TrajectoryCapture> captures,
        List<TrajectoryTask> tasks) {
}
