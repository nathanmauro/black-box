package dev.nathan.sbaagentic.workflow;

public record EnqueueTaskRequest(
        String specId,
        String title,
        String lane,
        int priority,
        String actor) {
}
