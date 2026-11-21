package dev.nathan.sbaagentic.memory.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;

import dev.nathan.sbaagentic.memory.ProjectBroadcast;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class BroadcastDigestTest {

    private static final Instant NOW = Instant.parse("2026-11-21T12:00:00Z");

    @Test
    void hazardsThenTentaclesThenInvariantsLouderAndNewerFirst() {
        List<ProjectBroadcast> active = List.of(
                broadcast("fact", "invariant", "blocker", "A fact.", NOW),
                broadcast("old-warn", "hazard", "warn", "Older warning.", NOW.minusSeconds(60)),
                broadcast("thread", "tentacle", "info", "A thread.", NOW),
                broadcast("new-warn", "hazard", "warn", "Newer warning.", NOW),
                broadcast("stop", "hazard", "blocker", "Stop.", NOW.minusSeconds(3600)));

        BroadcastDigest digest = BroadcastDigest.select(active);

        assertThat(digest.items())
                .extracting(ProjectBroadcast::key)
                .containsExactly("stop", "new-warn", "old-warn", "thread", "fact");
        assertThat(digest.omitted()).isZero();
    }

    @Test
    void neverExceedsEightItems() {
        List<ProjectBroadcast> active = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            active.add(broadcast("k" + i, "invariant", "info", "Short.", NOW));
        }

        BroadcastDigest digest = BroadcastDigest.select(active);

        assertThat(digest.items()).hasSize(8);
        assertThat(digest.omitted()).isEqualTo(4);
    }

    @Test
    void stopsAtTheFirstBroadcastThatWouldOverflowTheCharacterBudget() {
        List<ProjectBroadcast> active = List.of(
                broadcast("h1", "hazard", "blocker", "x".repeat(278), NOW),
                broadcast("h2", "hazard", "blocker", "x".repeat(278), NOW.minusSeconds(1)),
                broadcast("h3", "hazard", "blocker", "x".repeat(278), NOW.minusSeconds(2)),
                broadcast("h4", "hazard", "blocker", "x".repeat(278), NOW.minusSeconds(3)),
                broadcast("h5", "hazard", "blocker", "x".repeat(278), NOW.minusSeconds(4)),
                broadcast("h6", "hazard", "warn", "x".repeat(278), NOW),
                broadcast("tiny", "invariant", "info", "ok", NOW));

        BroadcastDigest digest = BroadcastDigest.select(active);
        int usedChars = digest.items().stream().mapToInt(BroadcastDigest::cost).sum();

        assertThat(digest.items()).extracting(ProjectBroadcast::key).containsExactly("h1", "h2", "h3", "h4", "h5");
        assertThat(usedChars).isLessThanOrEqualTo(BroadcastDigest.MAX_CHARS);
        // The tiny invariant does not jump the queue past the hazard that did not fit.
        assertThat(digest.omitted()).isEqualTo(2);
    }

    @Test
    void emptyInputYieldsTheEmptyDigest() {
        assertThat(BroadcastDigest.select(List.of())).isEqualTo(BroadcastDigest.EMPTY);
        assertThat(BroadcastDigest.select(null)).isEqualTo(BroadcastDigest.EMPTY);
    }

    private static ProjectBroadcast broadcast(
            String key, String kind, String severity, String message, Instant updatedAt) {

        return new ProjectBroadcast(
                key,
                "/work/black-box",
                key,
                kind,
                message,
                severity,
                "claude",
                null,
                updatedAt,
                updatedAt,
                null,
                null,
                null,
                null,
                null,
                ProjectBroadcast.STATUS_ACTIVE,
                false);
    }
}
