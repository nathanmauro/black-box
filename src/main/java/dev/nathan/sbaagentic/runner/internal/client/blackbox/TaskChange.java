package dev.nathan.sbaagentic.runner.internal.client.blackbox;

public record TaskChange(TaskSnapshot snapshot, TaskEvent event) {
}
