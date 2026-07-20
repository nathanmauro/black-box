package dev.nathan.sbaagentic.workflow;

public record TaskChange(TaskSnapshot snapshot, TaskEvent event) {
}
