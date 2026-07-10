package dev.nathan.sbaagentic.task;

import java.util.Locale;

public enum TaskEventType {
    CREATED("task.created"),
    CLAIMED("task.claimed"),
    BLOCKED("task.blocked"),
    COMPLETED("task.completed"),
    RESET("task.reset"),
    CANCELLED("task.cancelled");

    private final String value;

    TaskEventType(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static TaskEventType fromValue(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Task event type is required");
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (TaskEventType type : values()) {
            if (type.value.equals(normalized)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown task event type: " + value);
    }
}
