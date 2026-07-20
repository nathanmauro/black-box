package dev.nathan.sbaagentic.workflow;

import java.time.Instant;

public record Task(
        String id,
        String specId,
        String projectKey,
        String title,
        String lane,
        TaskStatus status,
        int priority,
        String createdBy,
        String claimedBy,
        String blockedReason,
        String resultHandoffId,
        Instant createdAt,
        Instant updatedAt) {
}
