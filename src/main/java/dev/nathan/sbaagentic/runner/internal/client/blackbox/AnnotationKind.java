package dev.nathan.sbaagentic.runner.internal.client.blackbox;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonValue;

public enum AnnotationKind {
    NOTE("note"),
    STEER("steer"),
    PROGRESS("progress"),
    PLAN("plan"),
    REVIEW("review"),
    APPROVAL("approval"),
    WORKER_SESSION("worker_session"),
    ENGINE("engine");

    private final String value;

    AnnotationKind(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    public static AnnotationKind fromValue(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Annotation kind is required");
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (AnnotationKind kind : values()) {
            if (kind.value.equals(normalized)) {
                return kind;
            }
        }
        throw new IllegalArgumentException("Unknown annotation kind: " + value);
    }
}
