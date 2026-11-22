package dev.nathan.sbaagentic.memory;

/**
 * Published after a broadcast is set or cleared so live surfaces can refresh. {@code change} is
 * {@code set} or {@code cleared}. It is a refresh hint: readers reload from {@code /api/broadcasts}.
 */
public record BroadcastChanged(String projectScope, String key, String kind, String change) {

    public static final String CHANGE_SET = "set";
    public static final String CHANGE_CLEARED = "cleared";
}
