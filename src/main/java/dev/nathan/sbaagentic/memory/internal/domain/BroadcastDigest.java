package dev.nathan.sbaagentic.memory.internal.domain;

import dev.nathan.sbaagentic.memory.ProjectBroadcast;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The bounded slice of a project's active broadcasts that recall attaches. Recall output opens a
 * session, so the digest is capped by count and by characters, and it fills in priority order:
 * hazards, then tentacles, then invariants; louder severity first; most recently touched first.
 */
public record BroadcastDigest(List<ProjectBroadcast> items, int omitted) {

    public static final int MAX_ITEMS = 8;
    public static final int MAX_CHARS = 1_500;

    public static final BroadcastDigest EMPTY = new BroadcastDigest(List.of(), 0);

    public static final Comparator<ProjectBroadcast> PRIORITY = Comparator.comparingInt(BroadcastDigest::kindRank)
            .thenComparing(
                    Comparator.comparingInt(BroadcastDigest::severityRank).reversed())
            .thenComparing(BroadcastDigest::updatedAt, Comparator.reverseOrder())
            .thenComparing(ProjectBroadcast::key, Comparator.nullsLast(Comparator.naturalOrder()));

    public BroadcastDigest {
        items = items == null ? List.of() : List.copyOf(items);
    }

    public static BroadcastDigest select(List<ProjectBroadcast> active) {

        return select(active, MAX_ITEMS, MAX_CHARS);
    }

    /**
     * Stops at the first broadcast that would overflow either cap rather than skipping ahead, so a
     * short low-priority invariant never displaces a longer hazard that did not fit.
     */
    public static BroadcastDigest select(List<ProjectBroadcast> active, int maxItems, int maxChars) {
        if (active == null || active.isEmpty()) {

            return EMPTY;
        }
        List<ProjectBroadcast> ordered = new ArrayList<>(active);
        ordered.sort(PRIORITY);

        List<ProjectBroadcast> kept = new ArrayList<>();
        int usedChars = 0;
        for (ProjectBroadcast broadcast : ordered) {
            int cost = cost(broadcast);
            if (kept.size() >= maxItems || usedChars + cost > maxChars) {
                break;
            }
            kept.add(broadcast);
            usedChars += cost;
        }

        return new BroadcastDigest(kept, ordered.size() - kept.size());
    }

    /** The characters an agent actually reads: the key and the message. */
    public static int cost(ProjectBroadcast broadcast) {

        return length(broadcast.key()) + length(broadcast.message());
    }

    private static int length(String value) {

        return value == null ? 0 : value.length();
    }

    private static int kindRank(ProjectBroadcast broadcast) {
        try {

            return BroadcastKind.parse(broadcast.kind()).recallRank();
        } catch (IllegalArgumentException ex) {

            return BroadcastKind.values().length;
        }
    }

    private static int severityRank(ProjectBroadcast broadcast) {

        return BroadcastSeverity.rankOf(broadcast.severity());
    }

    private static Instant updatedAt(ProjectBroadcast broadcast) {

        return broadcast.updatedAt() == null ? Instant.EPOCH : broadcast.updatedAt();
    }
}
