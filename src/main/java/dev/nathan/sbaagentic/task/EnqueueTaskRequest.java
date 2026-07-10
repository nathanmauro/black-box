package dev.nathan.sbaagentic.task;

public record EnqueueTaskRequest(
        String specId,
        String title,
        String lane,
        int priority,
        String actor) {
}
