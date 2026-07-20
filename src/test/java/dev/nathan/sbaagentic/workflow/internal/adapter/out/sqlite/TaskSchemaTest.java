package dev.nathan.sbaagentic.workflow.internal.adapter.out.sqlite;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import static org.assertj.core.api.Assertions.assertThat;

class TaskSchemaTest {

    @Test
    void schemaIsIdempotentAndCreatesApprovedQueueTablesIndexesAndForeignKeys(@TempDir Path tempDir) {
        DriverManagerDataSource dataSource = sqliteDataSource(tempDir.resolve("schema.db"));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        runSchema(dataSource);
        runSchema(dataSource);

        assertThat(jdbc.queryForList("""
                SELECT name
                  FROM sqlite_master
                 WHERE type = 'table'
                   AND name IN ('specs', 'tasks', 'task_events')
                 ORDER BY name
                """, String.class)).containsExactly("specs", "task_events", "tasks");
        assertThat(jdbc.queryForList("""
                SELECT name
                  FROM sqlite_master
                 WHERE type = 'index'
                   AND name IN ('idx_tasks_claimable', 'idx_task_events_task')
                 ORDER BY name
                """, String.class)).containsExactly("idx_task_events_task", "idx_tasks_claimable");
        assertThat(jdbc.queryForList("PRAGMA foreign_key_list(tasks)"))
                .anySatisfy(row -> {
                    assertThat(row.get("table")).isEqualTo("specs");
                    assertThat(row.get("from")).isEqualTo("spec_id");
                    assertThat(row.get("to")).isEqualTo("id");
                });
        assertThat(jdbc.queryForList("PRAGMA foreign_key_list(task_events)"))
                .anySatisfy(row -> {
                    assertThat(row.get("table")).isEqualTo("tasks");
                    assertThat(row.get("from")).isEqualTo("task_id");
                    assertThat(row.get("to")).isEqualTo("id");
                });
    }

    @Test
    void rerunningSchemaPreservesExistingSessionsEventsAndTheirConstraints(@TempDir Path tempDir) {
        DriverManagerDataSource dataSource = sqliteDataSource(tempDir.resolve("upgrade.db"));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        runSchema(dataSource);
        jdbc.update("""
                INSERT INTO agent_sessions (
                    id, source, client_session_id, title, title_rank, cwd, summary,
                    started_at, last_seen_at, event_count
                )
                VALUES ('session-1', 'codex', 'client-1', 'Existing session', 5, '/repo', 'summary',
                        '2026-07-09T00:00:00Z', '2026-07-09T00:01:00Z', 1)
                """);
        jdbc.update("""
                INSERT INTO agent_events (
                    id, session_id, source, client_session_id, turn_id, event_type, role, text,
                    tool_name, tool_input_json, tool_output_json, metadata_json, observed_at
                )
                VALUES ('event-1', 'session-1', 'codex', 'client-1', 'turn-1', 'Handoff', 'assistant',
                        'Existing event', NULL, NULL, NULL, '{"kind":"handoff"}', '2026-07-09T00:01:00Z')
                """);
        Map<String, String> definitionsBefore = Map.of(
                "agent_sessions", tableDefinition(jdbc, "agent_sessions"),
                "agent_events", tableDefinition(jdbc, "agent_events"));

        runSchema(dataSource);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM agent_sessions", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM agent_events", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForMap("SELECT * FROM agent_sessions WHERE id = 'session-1'"))
                .containsEntry("source", "codex")
                .containsEntry("client_session_id", "client-1")
                .containsEntry("title", "Existing session")
                .containsEntry("event_count", 1);
        assertThat(jdbc.queryForMap("SELECT * FROM agent_events WHERE id = 'event-1'"))
                .containsEntry("session_id", "session-1")
                .containsEntry("text", "Existing event")
                .containsEntry("metadata_json", "{\"kind\":\"handoff\"}");
        assertThat(tableDefinition(jdbc, "agent_sessions")).isEqualTo(definitionsBefore.get("agent_sessions"));
        assertThat(tableDefinition(jdbc, "agent_events")).isEqualTo(definitionsBefore.get("agent_events"));

        List<Map<String, Object>> sessionColumns = jdbc.queryForList("PRAGMA table_info(agent_sessions)");
        assertThat(sessionColumns)
                .filteredOn(column -> List.of("source", "client_session_id", "title", "started_at", "last_seen_at")
                        .contains(column.get("name")))
                .allSatisfy(column -> assertThat(column.get("notnull")).isEqualTo(1));
        assertThat(jdbc.queryForList("PRAGMA foreign_key_list(agent_events)"))
                .anySatisfy(row -> {
                    assertThat(row.get("table")).isEqualTo("agent_sessions");
                    assertThat(row.get("from")).isEqualTo("session_id");
                    assertThat(row.get("to")).isEqualTo("id");
                });
    }

    private static DriverManagerDataSource sqliteDataSource(Path database) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource("jdbc:sqlite:" + database);
        dataSource.setDriverClassName("org.sqlite.JDBC");
        return dataSource;
    }

    private static void runSchema(DriverManagerDataSource dataSource) {
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(dataSource);
    }

    private static String tableDefinition(JdbcTemplate jdbc, String table) {
        return jdbc.queryForObject(
                "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = ?",
                String.class,
                table);
    }
}
