package dev.nathan.sbaagentic.recording;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nathan.sbaagentic.recording.internal.adapter.out.sqlite.EventFtsIndex;
import dev.nathan.sbaagentic.recording.internal.adapter.out.sqlite.RecordingSqlStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

/** Identical feed-boundary grader on both pinned revisions; no post-fix helpers or Boot startup. */
class ChronologyFixtureContractTest {
    private static final Instant BASE = Instant.parse("2026-10-03T12:00:00Z");
    private static Path ownedRoot;
    private Connection connection;
    private JdbcTemplate jdbc;
    private RecordingSqlStore store;
    private TransactionTemplate transactions;

    @BeforeAll
    static void createOwnedRoot() throws IOException {
        Path target = Files.createDirectories(Path.of("target").toAbsolutePath());
        ownedRoot = Files.createTempDirectory(target, "chronology-fixture-");
        // Keep the SQLite JNI extraction in the same owned tree, not the user's temp settings.
        System.setProperty("org.sqlite.tmpdir", ownedRoot.toString());
    }

    @BeforeEach
    void createDatabase() throws Exception {
        Path directory = Files.createTempDirectory(ownedRoot, "case-");
        connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("events.db"));
        var source = new SingleConnectionDataSource(connection, true);
        jdbc = new JdbcTemplate(source);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(source);
        Clock clock = Clock.fixed(BASE, ZoneOffset.UTC);
        var fts = new EventFtsIndex(jdbc, clock);
        fts.ensureFtsSchema();
        store = new RecordingSqlStore(jdbc, new ObjectMapper(), clock, fts);
        store.ensureSchema();
    }

    @AfterEach
    void closeDatabase() throws Exception {
        if (connection != null) connection.close();
    }

    @AfterAll
    static void removeOwnedTree() throws IOException {
        if (ownedRoot == null)

            return;

        try (var paths = Files.walk(ownedRoot)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    @Test
    void fractionalEventsPrecedeWholeSecondOnFirstPage() {
        AgentEvent whole = event(BASE, "whole");
        AgentEvent fractional = event(BASE.plusMillis(100), "fractional");
        assertEquals(List.of(fractional.id(), whole.id()), ids(feed(null, null, null, null, 2)));
    }

    @Test
    void mixedPrecisionPagesRetainExactChronologicalOrder() {
        // Append deliberately out of order; preserve late/backdated evidence rather than append order.
        List<AgentEvent> events = List.of(
                event(BASE.plusNanos(100_001_001), "nine"),
                event(BASE.plusSeconds(1), "whole-next"),
                event(BASE, "whole"),
                event(BASE.plusMillis(100), "three"),
                event(BASE.minusNanos(1), "previous-nanosecond"),
                event(BASE.plusNanos(100_001_000), "six"),
                event(BASE.plusNanos(1), "next-nanosecond"));
        List<String> expected = events.stream()
                .sorted(Comparator.comparing(AgentEvent::observedAt).reversed())
                .map(AgentEvent::id)
                .toList();
        assertEquals(expected, pages(null, null, 2, events.size()));
    }

    @Test
    void sinceIncludesNextNanosecondAndExcludesPreviousNanosecond() {
        AgentEvent boundary = event(BASE, "boundary");
        AgentEvent after = event(BASE.plusNanos(1), "after");
        event(BASE.minusNanos(1), "before");
        assertEquals(List.of(after.id(), boundary.id()), ids(feed(null, null, BASE.toString(), null, 10)));
    }

    @Test
    void storedTimestampTextRemainsUnchanged() {
        List<Instant> times = List.of(BASE, BASE.plusMillis(100), BASE.plusNanos(100_001_000), BASE.plusNanos(1));
        for (Instant time : times) {
            AgentEvent captured = event(time, "timestamp");
            assertEquals(
                    time.toString(),
                    jdbc.queryForObject(
                            "SELECT observed_at FROM agent_events WHERE id = ?", String.class, captured.id()));
        }
        var before = jdbc.queryForList("SELECT id, observed_at, text, metadata_json FROM agent_events ORDER BY id");
        feed(null, null, BASE.minusSeconds(1).toString(), null, 10);
        store.ensureSchema();
        assertEquals(
                before, jdbc.queryForList("SELECT id, observed_at, text, metadata_json FROM agent_events ORDER BY id"));
    }

    @Test
    void equalInstantsRetainDescendingIdTieBreak() {
        List<String> expected =
                List.of(
                                event(BASE, "one").id(),
                                event(BASE, "two").id(),
                                event(BASE, "three").id())
                        .stream()
                        .sorted(Comparator.reverseOrder())
                        .toList();
        assertEquals(expected, pages(null, null, 1, expected.size()));
    }

    @Test
    void projectSourceAndTextBoundsRemainConjunctive() {
        AgentEvent older = event(BASE, "codex", "/fixture/alpha", "needle older");
        AgentEvent newer = event(BASE.plusSeconds(1), "codex", "/fixture/alpha", "needle newer");
        event(BASE.plusSeconds(2), "claude", "/fixture/alpha", "needle other source");
        event(BASE.plusSeconds(3), "codex", "/fixture/beta", "needle other project");
        event(BASE.plusSeconds(4), "codex", "/fixture/alpha", "unrelated content");
        assertEquals(
                List.of(newer.id(), older.id()), pages("source:codex project_exact:/fixture/alpha needle", null, 1, 2));
    }

    @Test
    void invalidCursorsRejectWithoutChangingRows() {
        event(BASE, "unchanged");
        var before = jdbc.queryForList("SELECT * FROM agent_events ORDER BY id");
        for (String cursor : List.of("missing-separator", "not-an-instant|id", BASE + "|", "|id")) {
            assertThrows(IllegalArgumentException.class, () -> feed(null, cursor, null, null, 2));
        }
        assertThrows(IllegalArgumentException.class, () -> feed(null, null, "not-an-instant", null, 2));
        assertEquals(before, jdbc.queryForList("SELECT * FROM agent_events ORDER BY id"));
    }

    private AgentEvent event(Instant time, String text) {

        return event(time, "codex", "/fixture/alpha", text);
    }

    private AgentEvent event(Instant time, String source, String project, String text) {
        var request = new EventIngestRequest(
                source,
                "fixture-" + project,
                null,
                "Observation",
                "assistant",
                text,
                project,
                null,
                null,
                null,
                Map.of("fixture", "synthetic"),
                time);

        return transactions.execute(status -> {
            var session = store.findOrCreateSession(request, time, "Fixture", 1);

            return store.saveEvent(request, session, time);
        });
    }

    private EventFeedResponse feed(String query, String before, String since, List<String> scopes, int limit) {

        return store.feed(query, false, before, since, scopes, limit, false);
    }

    private static List<String> ids(EventFeedResponse response) {

        return response.items().stream().map(EventFeedItem::id).toList();
    }

    private List<String> pages(String query, List<String> scopes, int limit, int expectedCount) {
        var result = new ArrayList<String>();
        var cursors = new HashSet<String>();
        String cursor = null;
        for (int attempt = 0; attempt <= expectedCount; attempt++) {
            EventFeedResponse response = feed(query, cursor, null, scopes, limit);
            assertTrue(response.items().size() <= limit);
            result.addAll(ids(response));
            if (response.nextBefore() == null) {
                assertEquals(expectedCount, result.size());
                assertEquals(result.size(), new HashSet<>(result).size());

                return result;
            }
            assertFalse(response.items().isEmpty());
            assertNotNull(response.nextBefore());
            assertTrue(cursors.add(response.nextBefore()), "cursor must advance");
            cursor = response.nextBefore();
        }
        throw new org.opentest4j.AssertionFailedError("pagination did not terminate within the fixture bound");
    }
}
