package dev.nathan.sbaagentic.memory.internal.domain;

import java.util.Locale;

/** How loudly a broadcast should land; ordered from quiet to loud. */
public enum BroadcastSeverity {
    INFO("info"),
    WARN("warn"),
    BLOCKER("blocker");

    private final String wireName;

    BroadcastSeverity(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {

        return wireName;
    }

    /** Higher ranks are louder. */
    public int rank() {

        return ordinal();
    }

    public static BroadcastSeverity parseOrDefault(String value, BroadcastSeverity fallback) {
        if (value == null || value.isBlank()) {

            return fallback;
        }
        String normalized = value.strip().toLowerCase(Locale.ROOT);
        for (BroadcastSeverity severity : values()) {
            if (severity.wireName.equals(normalized)) {

                return severity;
            }
        }

        throw new IllegalArgumentException("severity must be one of info, warn, or blocker.");
    }

    /** Unknown stored values sort as quiet instead of failing a read. */
    public static int rankOf(String value) {
        try {

            return parseOrDefault(value, INFO).rank();
        } catch (IllegalArgumentException ex) {

            return INFO.rank();
        }
    }
}
