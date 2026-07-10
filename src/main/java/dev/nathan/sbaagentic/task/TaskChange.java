package dev.nathan.sbaagentic.task;

public record TaskChange(TaskSnapshot snapshot, TaskEvent event) {
}
