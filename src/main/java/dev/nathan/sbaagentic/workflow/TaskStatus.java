package dev.nathan.sbaagentic.workflow;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonValue;

public enum TaskStatus {
    OPEN("open"),
    CLAIMED("claimed"),
    IN_PROGRESS("in_progress"),
    BLOCKED("blocked"),
    DONE("done"),
    CANCELLED("cancelled");

    private final String value;

    TaskStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    public static TaskStatus fromValue(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Task status is required");
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (TaskStatus status : values()) {
            if (status.value.equals(normalized)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown task status: " + value);
    }
}
