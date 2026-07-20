package dev.nathan.sbaagentic.runner.internal.client.blackbox;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonValue;

public enum SpecStatus {
    ACTIVE("active"),
    DONE("done"),
    ARCHIVED("archived");

    private final String value;

    SpecStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    public static SpecStatus fromValue(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Spec status is required");
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (SpecStatus status : values()) {
            if (status.value.equals(normalized)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown spec status: " + value);
    }
}
