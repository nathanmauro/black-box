package dev.nathan.sbaagentic.memory.internal.adapter.out.sqlite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.nathan.sbaagentic.memory.ProjectBroadcast;
import dev.nathan.sbaagentic.memory.internal.application.BroadcastClear;
import dev.nathan.sbaagentic.memory.internal.application.BroadcastClearResult;
import dev.nathan.sbaagentic.memory.internal.application.BroadcastListQuery;
import dev.nathan.sbaagentic.memory.internal.application.BroadcastService;
import dev.nathan.sbaagentic.memory.internal.application.BroadcastUpsert;
import dev.nathan.sbaagentic.memory.internal.domain.BroadcastDigest;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/** The broadcast service over a real SQLite file, with a clock the test moves by hand. */
class BroadcastSqlStoreTest {

    private static final String BLACK_BOX = "/work/black-box";
    private static final String COCKPIT = "/work/cockpit";

    private final MovableClock clock = new MovableClock(Instant.parse("2026-11-21T12:00:00Z"));

    private JdbcTemplate jdbc;
    private BroadcastService service;

    @BeforeEach
    void setUp() {
        Path database =
                Path.of(System.getProperty("java.io.tmpdir"), "bb-broadcast-store-test-" + UUID.randomUUID() + ".db");
        database.toFile().deleteOnExit();

        DriverManagerDataSource dataSource = new DriverManagerDataSource("jdbc:sqlite:" + database);
        dataSource.setDriverClassName("org.sqlite.JDBC");
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(dataSource);

        jdbc = new JdbcTemplate(dataSource);
        service = new BroadcastService(new BroadcastSqlStore(jdbc), scope -> List.of(scope), clock);
    }

    @Test
    void upsertBySameKeyRefreshesTheOpenRowInsteadOfAddingOne() {
        ProjectBroadcast first = service.set(hazard(BLACK_BOX, "dirty-main", "Main has 3 uncommitted edits."));
        clock.advance(Duration.ofMinutes(5));

        ProjectBroadcast second = service.set(new BroadcastUpsert(
                "codex",
                "s-2",
                BLACK_BOX + "/",
                "Dirty-Main",
                "hazard",
                "Main has 1 uncommitted edit.",
                "blocker",
                null,
                null));

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.createdAt()).isEqualTo(first.createdAt());
        assertThat(second.updatedAt()).isEqualTo(first.updatedAt().plus(Duration.ofMinutes(5)));
        assertThat(second.message()).isEqualTo("Main has 1 uncommitted edit.");
        assertThat(second.severity()).isEqualTo("blocker");
        assertThat(second.source()).isEqualTo("codex");
        assertThat(second.key()).isEqualTo("dirty-main");
        assertThat(second.projectScope()).isEqualTo(BLACK_BOX);
        assertThat(rowCount()).isEqualTo(1);
    }

    @Test
    void kindDefaultsDecideSeverityAndExpiry() {
        ProjectBroadcast hazard = service.set(hazard(BLACK_BOX, "lock", "Release build running."));
        ProjectBroadcast invariant = service.set(broadcast(BLACK_BOX, "freeze", "invariant", "API frozen.", null));
        ProjectBroadcast tentacle = service.set(broadcast(BLACK_BOX, "tangent", "tentacle", "Half-built.", null));

        assertThat(hazard.severity()).isEqualTo("warn");
        assertThat(hazard.expiresAt()).isEqualTo(clock.instant().plus(Duration.ofHours(72)));
        assertThat(invariant.severity()).isEqualTo("info");
        assertThat(invariant.expiresAt()).isNull();
        assertThat(tentacle.expiresAt()).isNull();
        assertThat(List.of(hazard, invariant, tentacle))
                .allSatisfy(b -> assertThat(b.status()).isEqualTo("active"));
    }

    @Test
    void clearIsIdempotentAndKeepsHistory() {
        service.set(hazard(BLACK_BOX, "lock", "Release build running."));

        BroadcastClearResult first =
                service.clear(new BroadcastClear("claude", "s-1", BLACK_BOX, "lock", "build finished"));
        BroadcastClearResult again = service.clear(new BroadcastClear("claude", "s-1", BLACK_BOX, "lock", null));
        BroadcastClearResult unknown = service.clear(new BroadcastClear("claude", "s-1", BLACK_BOX, "never-set", null));

        assertThat(first.cleared()).isTrue();
        assertThat(first.broadcast().status()).isEqualTo("cleared");
        assertThat(first.broadcast().clearedBy()).isEqualTo("claude:s-1");
        assertThat(first.broadcast().clearReason()).isEqualTo("build finished");
        assertThat(again.cleared()).isFalse();
        assertThat(again.broadcast().id()).isEqualTo(first.broadcast().id());
        assertThat(unknown.cleared()).isFalse();
        assertThat(unknown.broadcast()).isNull();

        assertThat(list(BLACK_BOX, null, false, false).broadcasts()).isEmpty();
        assertThat(service.list(new BroadcastListQuery(BLACK_BOX, null, null, null, null, true, null))
                        .broadcasts())
                .singleElement()
                .satisfies(b -> assertThat(b.status()).isEqualTo("cleared"));
    }

    @Test
    void settingAKeyAfterItWasClearedStartsAFreshRow() {
        ProjectBroadcast original = service.set(hazard(BLACK_BOX, "lock", "First lock."));
        service.clear(new BroadcastClear("claude", null, BLACK_BOX, "lock", "done"));

        ProjectBroadcast reopened = service.set(hazard(BLACK_BOX, "lock", "Second lock."));

        assertThat(reopened.id()).isNotEqualTo(original.id());
        assertThat(rowCount()).isEqualTo(2);
    }

    @Test
    void ttlExpiryDropsHazardsFromRecallAndListingButKeepsTentaclesOpen() {
        service.set(new BroadcastUpsert("claude", "s", BLACK_BOX, "lock", "hazard", "Short lock.", null, 2, null));
        service.set(new BroadcastUpsert(
                "claude", "s", BLACK_BOX, "spike", "tentacle", "Spike in a worktree.", null, 2, null));
        assertThat(service.digestFor(List.of(BLACK_BOX)).items()).hasSize(2);

        clock.advance(Duration.ofHours(2));

        assertThat(service.digestFor(List.of(BLACK_BOX)).items()).isEmpty();
        assertThat(list(BLACK_BOX, null, false, false).broadcasts())
                .singleElement()
                .satisfies(b -> {
                    assertThat(b.key()).isEqualTo("spike");
                    assertThat(b.status()).isEqualTo("expired");
                    assertThat(b.stale()).isTrue();
                });
    }

    @Test
    void refreshingAnExpiredBroadcastRevivesTheSameRow() {
        ProjectBroadcast first =
                service.set(new BroadcastUpsert("claude", "s", BLACK_BOX, "lock", "hazard", "Lock.", null, 1, null));
        clock.advance(Duration.ofHours(3));

        ProjectBroadcast refreshed =
                service.set(new BroadcastUpsert("claude", "s", BLACK_BOX, "lock", "hazard", "Lock.", null, 1, null));

        assertThat(refreshed.id()).isEqualTo(first.id());
        assertThat(refreshed.status()).isEqualTo("active");
        assertThat(service.digestFor(List.of(BLACK_BOX)).items()).hasSize(1);
    }

    @Test
    void staleTentaclesAreListedAcrossProjectsLongestNeglectedFirst() {
        service.set(broadcast(BLACK_BOX, "old-spike", "tentacle", "Vector spike never merged.", null));
        clock.advance(Duration.ofHours(10));
        service.set(broadcast(COCKPIT, "hook-tangent", "tentacle", "Hook rewrite paused.", null));
        service.set(broadcast(COCKPIT, "dirty-tree", "hazard", "Not a tentacle.", null));
        service.set(broadcast(COCKPIT, "later", "tentacle", "Resume next week.", "2026-12-01"));
        clock.advance(Duration.ofHours(75));
        service.set(broadcast(BLACK_BOX, "fresh", "tentacle", "Just started.", null));

        var all = service.list(new BroadcastListQuery(null, "tentacle", null, null, true, null, null));
        var stale = service.list(new BroadcastListQuery(null, null, true, null, true, null, null));
        var staleSooner = service.list(new BroadcastListQuery(null, "tentacle", true, 1, true, null, null));

        assertThat(all.broadcasts())
                .extracting(ProjectBroadcast::key)
                .containsExactlyInAnyOrder("old-spike", "hook-tangent", "later", "fresh");
        assertThat(stale.broadcasts()).extracting(ProjectBroadcast::key).containsExactly("old-spike", "hook-tangent");
        assertThat(stale.broadcasts())
                .extracting(ProjectBroadcast::projectScope)
                .containsExactly(BLACK_BOX, COCKPIT);
        assertThat(stale.staleAfterHours()).isEqualTo(72);
        // The explicit follow-up keeps "later" quiet even under a one-hour threshold.
        assertThat(staleSooner.broadcasts())
                .extracting(ProjectBroadcast::key)
                .containsExactly("old-spike", "hook-tangent");
    }

    @Test
    void passedFollowUpMakesATentacleStaleRegardlessOfAge() {
        service.set(broadcast(BLACK_BOX, "check-ci", "tentacle", "Check CI after merge.", "2026-11-21T13:00:00Z"));

        assertThat(list(BLACK_BOX, "tentacle", true, false).broadcasts()).isEmpty();

        clock.advance(Duration.ofHours(1));

        assertThat(list(BLACK_BOX, "tentacle", true, false).broadcasts())
                .singleElement()
                .satisfies(b -> assertThat(b.stale()).isTrue());
    }

    @Test
    void digestOrdersByKindThenSeverityAndHonorsTheCaps() {
        service.set(broadcast(BLACK_BOX, "fact", "invariant", "Schema is frozen.", null));
        service.set(broadcast(BLACK_BOX, "thread", "tentacle", "Half-done migration.", null));
        service.set(new BroadcastUpsert("claude", "s", BLACK_BOX, "warn-lock", "hazard", "Lock.", "warn", null, null));
        service.set(new BroadcastUpsert("claude", "s", BLACK_BOX, "stop", "hazard", "Stop.", "blocker", null, null));
        for (int i = 0; i < 6; i++) {
            service.set(broadcast(BLACK_BOX, "fact-" + i, "invariant", "Fact " + i + ".", null));
        }
        service.set(broadcast(COCKPIT, "elsewhere", "hazard", "Other project.", null));

        BroadcastDigest digest = service.digestFor(List.of(BLACK_BOX));

        assertThat(digest.items()).hasSize(BroadcastDigest.MAX_ITEMS);
        assertThat(digest.omitted()).isEqualTo(2);
        assertThat(digest.items())
                .extracting(ProjectBroadcast::key)
                .startsWith("stop", "warn-lock", "thread")
                .doesNotContain("elsewhere");
    }

    @Test
    void rejectsInputThatWouldBloatOrConfuseTheLiveSet() {
        assertThatThrownBy(() -> service.set(hazard(BLACK_BOX, "bad key!", "Message.")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("key");
        assertThatThrownBy(() -> service.set(hazard(BLACK_BOX, "long", "x".repeat(281))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("280");
        assertThatThrownBy(() -> service.set(hazard(" ", "key", "Message.")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("repo");
        assertThatThrownBy(() -> service.set(broadcast(BLACK_BOX, "k", "rumor", "Message.", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("kind");
        assertThatThrownBy(() -> service.list(new BroadcastListQuery(null, null, null, null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("allProjects");
        assertThatThrownBy(
                        () -> service.list(new BroadcastListQuery(BLACK_BOX, "hazard", true, null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tentacles");
    }

    private BroadcastUpsert hazard(String repo, String key, String message) {

        return broadcast(repo, key, "hazard", message, null);
    }

    private static BroadcastUpsert broadcast(String repo, String key, String kind, String message, String followUp) {

        return new BroadcastUpsert("claude", "s-1", repo, key, kind, message, null, null, followUp);
    }

    private dev.nathan.sbaagentic.memory.internal.application.BroadcastListResponse list(
            String project, String kind, boolean staleOnly, boolean includeCleared) {

        return service.list(new BroadcastListQuery(project, kind, staleOnly, null, null, includeCleared, null));
    }

    private int rowCount() {

        return jdbc.queryForObject("SELECT count(*) FROM project_broadcasts", Integer.class);
    }

    /** A clock that only moves when the test says so. */
    private static final class MovableClock extends Clock {

        private Instant now;

        MovableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {

            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {

            return this;
        }

        @Override
        public Instant instant() {

            return now;
        }
    }
}
