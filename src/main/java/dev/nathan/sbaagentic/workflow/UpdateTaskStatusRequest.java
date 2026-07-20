package dev.nathan.sbaagentic.workflow;

public record UpdateTaskStatusRequest(
        String taskId,
        String actor,
        TaskStatus status,
        String blockedReason) {
}
