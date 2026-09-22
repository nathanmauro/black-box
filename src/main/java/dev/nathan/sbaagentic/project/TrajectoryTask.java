package dev.nathan.sbaagentic.project;

import java.time.Instant;

public record TrajectoryTask(String id, String title, String status, int priority, Instant updatedAt) {}
