package dev.nathan.sbaagentic.runner.internal.client.blackbox;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonValue;

public enum LinkType {
    SPAWNED("spawned"),
    STEERED("steered"),
    CONTINUED("continued");

    private final String value;

    LinkType(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    public static LinkType fromValue(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Link type is required");
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (LinkType type : values()) {
            if (type.value.equals(normalized)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown link type: " + value);
    }
}
