package dev.nathan.sbaagentic.task;

public record UpdateTaskStatusRequest(
        String taskId,
        String actor,
        TaskStatus status,
        String blockedReason) {
}
