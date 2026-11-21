package dev.nathan.sbaagentic.memory.internal.domain;

import java.time.Duration;
import java.util.Locale;
import java.util.Optional;

/**
 * What a project broadcast tells the next session. The order of the constants is the recall
 * priority: a live hazard outranks an unfinished thread, which outranks a standing fact.
 */
public enum BroadcastKind {

    /** An active lock, dirty tree, or warning; decays by default because hazards go stale. */
    HAZARD("hazard", BroadcastSeverity.WARN, Duration.ofHours(72)),

    /** A started-but-unfinished thread; sticky because only an explicit clear closes it. */
    TENTACLE("tentacle", BroadcastSeverity.INFO, null),

    /** A standing project fact; sticky until someone clears it. */
    INVARIANT("invariant", BroadcastSeverity.INFO, null);

    private final String wireName;
    private final BroadcastSeverity defaultSeverity;
    private final Duration defaultTtl;

    BroadcastKind(String wireName, BroadcastSeverity defaultSeverity, Duration defaultTtl) {
        this.wireName = wireName;
        this.defaultSeverity = defaultSeverity;
        this.defaultTtl = defaultTtl;
    }

    public String wireName() {

        return wireName;
    }

    public BroadcastSeverity defaultSeverity() {

        return defaultSeverity;
    }

    public Optional<Duration> defaultTtl() {

        return Optional.ofNullable(defaultTtl);
    }

    /** Lower ranks surface first in recall. */
    public int recallRank() {

        return ordinal();
    }

    /** Blank means the caller did not choose; a hazard is the original broadcast use case. */
    public static BroadcastKind parseOrDefault(String value) {
        if (value == null || value.isBlank()) {

            return HAZARD;
        }

        return parse(value);
    }

    public static BroadcastKind parse(String value) {
        String normalized = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
        for (BroadcastKind kind : values()) {
            if (kind.wireName.equals(normalized)) {

                return kind;
            }
        }

        throw new IllegalArgumentException("kind must be one of hazard, invariant, or tentacle.");
    }
}
