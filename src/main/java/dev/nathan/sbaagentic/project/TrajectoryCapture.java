package dev.nathan.sbaagentic.project;

import java.time.Instant;
import java.util.List;

public record TrajectoryCapture(
        String id,
        String kind,
        String sessionId,
        String sessionTitle,
        String clientSessionId,
        String source,
        String headline,
        String text,
        String rationale,
        List<String> alternatives,
        List<String> openLoops,
        String nextAction,
        String toAgent,
        Double confidence,
        List<TrajectoryPath> paths,
        Instant observedAt) {}
