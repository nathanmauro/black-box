package dev.nathan.sbaagentic.recording;

import java.util.Locale;

/**
 * The one normalization rule for comparing {@code eventType} across modules: lowercase, then
 * strip everything but letters and digits, so {@code subagent_stop}, {@code SubagentStop}, and
 * {@code subagent-stop} all collapse to the same {@code subagentstop} token. Recording (ingest's
 * final-event check, the session-mint lineage stamp) and workflow (the subagent link listener)
 * must all route event-type comparisons through this method rather than reimplementing their own
 * lowercase-only or separator-stripping variant — divergence here previously let a snake_case
 * {@code subagent_stop} event stamp {@code spawned_by} without ever creating the link or firing
 * {@code SessionStopped}, producing an invisible orphan child session.
 */
public final class EventTypes {

    private EventTypes() {
    }

    public static String normalize(String eventType) {
        return eventType == null ? "" : eventType.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
