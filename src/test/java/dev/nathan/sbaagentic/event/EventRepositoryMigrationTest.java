package dev.nathan.sbaagentic.recording;

import dev.nathan.sbaagentic.recording.internal.adapter.out.sqlite.RecordingSqlStore;

import java.nio.file.Path;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.nathan.sbaagentic.recording.TitleRank;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the {@code title_rank} migration against a database that predates
 * the column. The Spring integration tests always run {@code schema.sql} first,
 * so they only cover fresh databases; this covers the upgrade path that real
 * on-disk databases take.
 */
class EventRepositoryMigrationTest {

    @Test
    void addsAndBackfillsTitleRankOnPreRankingDatabase(@TempDir Path tempDir) {
        DriverManagerDataSource dataSource =
                new DriverManagerDataSource("jdbc:sqlite:" + tempDir.resolve("legacy.db"));
        dataSource.setDriverClassName("org.sqlite.JDBC");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        // Recreate the pre-ranking schema (no title_rank) with one existing session.
        jdbc.execute("""
                CREATE TABLE agent_sessions (
                    id TEXT PRIMARY KEY,
                    source TEXT NOT NULL,
                    client_session_id TEXT NOT NULL,
                    title TEXT NOT NULL,
                    cwd TEXT,
                    summary TEXT,
                    started_at TEXT NOT NULL,
                    last_seen_at TEXT NOT NULL,
                    event_count INTEGER NOT NULL DEFAULT 0,
                    UNIQUE (source, client_session_id)
                )
                """);
        jdbc.update("""
                INSERT INTO agent_sessions (id, source, client_session_id, title, started_at, last_seen_at, event_count)
                VALUES ('s1', 'claude', 'legacy-session', 'Old title', '2026-05-21T12:00:00Z', '2026-05-21T12:00:00Z', 3)
                """);

        RecordingSqlStore repository = new RecordingSqlStore(
                jdbc,
                new ObjectMapper());
        repository.ensureSchema();

        // The legacy session keeps its title but is protected (LEGACY) so only an AI retitle replaces it.
        assertThat(jdbc.queryForObject("SELECT title_rank FROM agent_sessions WHERE id = 's1'", Integer.class))
                .isEqualTo(TitleRank.LEGACY);

        // Running again is an idempotent no-op: no error, rank unchanged.
        repository.ensureSchema();
        assertThat(jdbc.queryForObject("SELECT title_rank FROM agent_sessions WHERE id = 's1'", Integer.class))
                .isEqualTo(TitleRank.LEGACY);
    }

    @Test
    void addsSpawnedByColumnOnPreLineageDatabase(@TempDir Path tempDir) {
        DriverManagerDataSource dataSource =
                new DriverManagerDataSource("jdbc:sqlite:" + tempDir.resolve("pre-lineage.db"));
        dataSource.setDriverClassName("org.sqlite.JDBC");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        // Recreate the pre-lineage schema (title_rank present, no spawned_by) with one session.
        jdbc.execute("""
                CREATE TABLE agent_sessions (
                    id TEXT PRIMARY KEY,
                    source TEXT NOT NULL,
                    client_session_id TEXT NOT NULL,
                    title TEXT NOT NULL,
                    title_rank INTEGER NOT NULL DEFAULT 1,
                    cwd TEXT,
                    summary TEXT,
                    started_at TEXT NOT NULL,
                    last_seen_at TEXT NOT NULL,
                    event_count INTEGER NOT NULL DEFAULT 0,
                    UNIQUE (source, client_session_id)
                )
                """);
        jdbc.update("""
                INSERT INTO agent_sessions (id, source, client_session_id, title, title_rank, started_at, last_seen_at, event_count)
                VALUES ('s1', 'claude', 'pre-lineage-session', 'Parent work', 3, '2026-07-20T12:00:00Z', '2026-07-20T12:00:00Z', 1)
                """);

        RecordingSqlStore repository = new RecordingSqlStore(
                jdbc,
                new ObjectMapper());
        repository.ensureSchema();

        // Existing sessions backfill as parents: spawned_by is NULL and maps through the record.
        assertThat(jdbc.queryForObject("SELECT spawned_by FROM agent_sessions WHERE id = 's1'", String.class))
                .isNull();
        assertThat(repository.findSessionById("s1").orElseThrow().spawnedBy()).isNull();

        // Running again is an idempotent no-op.
        repository.ensureSchema();
        assertThat(repository.findSessionById("s1").orElseThrow().spawnedBy()).isNull();
    }
}
