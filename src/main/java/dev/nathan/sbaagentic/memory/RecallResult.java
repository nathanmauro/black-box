package dev.nathan.sbaagentic.memory;

import java.util.List;

/**
 * The structured answer to "what did agents working on this already decide?" — the read side of the
 * write+query loop that is Black Box's reason to exist. {@code scope} echoes the repo-or-topic that
 * was matched, so a caller (human or agent) can see what the recall was anchored to.
 *
 * <p>{@code broadcasts} carries the project's live broadcast set when recall resolved to a project,
 * bounded by count and characters; {@code broadcastsOmitted} counts active broadcasts that did not
 * fit, so a caller knows to ask {@code listBroadcasts} for the rest.
 */
public record RecallResult(
        String scope,
        int withinHours,
        List<String> kinds,
        int count,
        List<RecalledItem> items,
        String mode,
        boolean truncated,
        List<RecalledBroadcast> broadcasts,
        int broadcastsOmitted) {

    public RecallResult {
        broadcasts = broadcasts == null ? List.of() : List.copyOf(broadcasts);
        broadcastsOmitted = Math.max(0, broadcastsOmitted);
    }

    public RecallResult(
            String scope, int withinHours, List<String> kinds, int count, List<RecalledItem> items, String mode) {
        this(scope, withinHours, kinds, count, items, mode, false);
    }

    public RecallResult(
            String scope,
            int withinHours,
            List<String> kinds,
            int count,
            List<RecalledItem> items,
            String mode,
            boolean truncated) {
        this(scope, withinHours, kinds, count, items, mode, truncated, List.of(), 0);
    }

    public RecallResult withBroadcasts(List<RecalledBroadcast> attached, int omitted) {

        return new RecallResult(scope, withinHours, kinds, count, items, mode, truncated, attached, omitted);
    }
}
