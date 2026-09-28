package dev.nathan.sbaagentic.recording;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nathan.sbaagentic.recording.internal.adapter.out.sqlite.EventFtsIndex;
import dev.nathan.sbaagentic.recording.internal.adapter.out.sqlite.HumanTurnBackfill;
import dev.nathan.sbaagentic.recording.internal.adapter.out.sqlite.RecordingSqlStore;
import java.nio.file.Path;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/** Upgrade path: a database created before human-turn columns existed is migrated and classified. */
class HumanTurnBackfillTest {

    private static final String REMINDER = "<system-reminder>\nboot context\n</system-reminder>";

    @Test
    void migratesClassifiesAndIsIdempotentAcrossVersionBumps(@TempDir Path tempDir) {
        DriverManagerDataSource dataSource =
                new DriverManagerDataSource("jdbc:sqlite:" + tempDir.resolve("pre-human.db"));
        dataSource.setDriverClassName("org.sqlite.JDBC");
        var schema = new ResourceDatabasePopulator(new ClassPathResource("schema.sql"));
        schema.execute(dataSource);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        // Recreate the pre-upgrade shape: no human columns, no classifier state.
        jdbc.execute("ALTER TABLE agent_events DROP COLUMN human_text");
        jdbc.execute("ALTER TABLE agent_sessions DROP COLUMN first_human_turn");
        jdbc.execute("DROP TABLE human_turn_state");

        session(jdbc, "junk", REMINDER, TitleRank.TEXT);
        event(jdbc, "junk-1", "junk", "UserPromptSubmit", REMINDER, "2026-09-01T10:00:01Z");
        event(jdbc, "junk-2", "junk", "UserPromptSubmit", REMINDER + "\nfirst real ask\nmore", "2026-09-01T10:00:02Z");
        event(jdbc, "junk-3", "junk", "UserPromptSubmit", "later ask", "2026-09-01T10:00:03Z");
        event(jdbc, "junk-4", "junk", "PostToolUse", "tool output", "2026-09-01T10:00:04Z");
        session(jdbc, "ai", "AI title", TitleRank.AI);
        event(jdbc, "ai-1", "ai", "UserPromptSubmit", "ai session ask", "2026-09-01T10:00:05Z");
        session(jdbc, "machine", "machine", TitleRank.TEXT);
        event(
                jdbc,
                "machine-1",
                "machine",
                "UserPromptSubmit",
                "<task-notification>x</task-notification>",
                "2026-09-01T10:00:06Z");

        schema.execute(dataSource);
        RecordingSqlStore store = new RecordingSqlStore(
                jdbc, new ObjectMapper(), Clock.systemUTC(), new EventFtsIndex(jdbc, Clock.systemUTC()));
        store.ensureSchema();
        store.ensureSchema();
        HumanTurnBackfill backfill =
                new HumanTurnBackfill(jdbc, new DataSourceTransactionManager(dataSource), Clock.systemUTC(), store);

        var first = backfill.run();

        assertThat(first.ran()).isTrue();
        assertThat(first.eventsChanged()).isEqualTo(3);
        assertThat(humanText(jdbc, "junk-1")).isNull();
        assertThat(humanText(jdbc, "junk-2")).isEqualTo("first real ask\nmore");
        assertThat(humanText(jdbc, "junk-3")).isEqualTo("later ask");
        assertThat(humanText(jdbc, "junk-4")).isNull();
        assertThat(firstTurn(jdbc, "junk")).isEqualTo("first real ask\nmore");
        assertThat(firstTurn(jdbc, "machine")).isNull();
        assertThat(jdbc.queryForObject("SELECT title FROM agent_sessions WHERE id = 'junk'", String.class))
                .isEqualTo("first real ask");
        assertThat(jdbc.queryForObject("SELECT title_rank FROM agent_sessions WHERE id = 'junk'", Integer.class))
                .isEqualTo(TitleRank.HUMAN);
        // AI and machine-only titles are untouched; the AI session still learns its first human turn.
        assertThat(jdbc.queryForObject("SELECT title FROM agent_sessions WHERE id = 'ai'", String.class))
                .isEqualTo("AI title");
        assertThat(jdbc.queryForObject("SELECT title_rank FROM agent_sessions WHERE id = 'ai'", Integer.class))
                .isEqualTo(TitleRank.AI);
        assertThat(firstTurn(jdbc, "ai")).isEqualTo("ai session ask");
        assertThat(jdbc.queryForObject("SELECT title FROM agent_sessions WHERE id = 'machine'", String.class))
                .isEqualTo("machine");
        assertThat(jdbc.queryForObject("SELECT text FROM agent_events WHERE id = 'junk-2'", String.class))
                .isEqualTo(REMINDER + "\nfirst real ask\nmore");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM sqlite_master WHERE name = 'idx_agent_events_human'", Integer.class))
                .isEqualTo(1);

        assertThat(backfill.run().ran()).isFalse();

        // A stale stored version reclassifies again and repairs drifted values.
        jdbc.update("UPDATE human_turn_state SET version = 0");
        jdbc.update("UPDATE agent_events SET human_text = 'stale' WHERE id = 'junk-3'");
        var second = backfill.run();
        assertThat(second.ran()).isTrue();
        assertThat(second.eventsChanged()).isEqualTo(1);
        assertThat(humanText(jdbc, "junk-3")).isEqualTo("later ask");
        assertThat(jdbc.queryForObject("SELECT version FROM human_turn_state", Integer.class))
                .isEqualTo(HumanTurns.VERSION);
        assertThat(backfill.run().ran()).isFalse();
    }

    private static void session(JdbcTemplate jdbc, String id, String title, int rank) {
        jdbc.update(
                "INSERT INTO agent_sessions (id, source, client_session_id, title, title_rank, started_at,"
                        + " last_seen_at, event_count) VALUES (?, 'claude', ?, ?, ?, '2026-09-01T10:00:00Z',"
                        + " '2026-09-01T10:00:00Z', 0)",
                id,
                id,
                title,
                rank);
    }

    private static void event(JdbcTemplate jdbc, String id, String sessionId, String type, String text, String at) {
        jdbc.update(
                "INSERT INTO agent_events (id, session_id, source, client_session_id, event_type, text, observed_at)"
                        + " VALUES (?, ?, 'claude', ?, ?, ?, ?)",
                id,
                sessionId,
                sessionId,
                type,
                text,
                at);
    }

    private static String humanText(JdbcTemplate jdbc, String id) {

        return jdbc.queryForObject("SELECT human_text FROM agent_events WHERE id = ?", String.class, id);
    }

    private static String firstTurn(JdbcTemplate jdbc, String id) {

        return jdbc.queryForObject("SELECT first_human_turn FROM agent_sessions WHERE id = ?", String.class, id);
    }
}
