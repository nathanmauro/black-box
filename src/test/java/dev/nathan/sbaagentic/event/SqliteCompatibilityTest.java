package dev.nathan.sbaagentic.event;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.nathan.sbaagentic.project.ProjectAliasRepository;
import dev.nathan.sbaagentic.project.ProjectAliasService;
import dev.nathan.sbaagentic.session.TitleRank;
import dev.nathan.sbaagentic.workflow.internal.adapter.out.sqlite.TaskRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import static org.assertj.core.api.Assertions.assertThat;

class SqliteCompatibilityTest {

    @Test
    void preRefactorDatabaseMigratesReadsAndAcceptsNewWrites(@TempDir Path tempDir) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:sqlite:" + tempDir.resolve("pre-refactor.db"));
        dataSource.setDriverClassName("org.sqlite.JDBC");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        new ResourceDatabasePopulator(new ClassPathResource("contracts/pre-refactor.sqlite.sql"))
                .execute(dataSource);
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(dataSource);

        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        EventRepository events = new EventRepository(
                jdbc,
                objectMapper,
                new ProjectAliasService(new ProjectAliasRepository(jdbc)));
        events.ensureSchema();
        TaskRepository tasks = new TaskRepository(jdbc, objectMapper);

        assertThat(events.findSessionById("legacy-session")).get()
                .extracting(session -> session.clientSessionId(), session -> session.title(), session -> session.eventCount())
                .containsExactly("legacy-client", "Legacy session", 1L);
        assertThat(jdbc.queryForObject(
                "SELECT title_rank FROM agent_sessions WHERE id = 'legacy-session'",
                Integer.class)).isEqualTo(TitleRank.LEGACY);
        assertThat(tasks.findTask("legacy-task")).get()
                .satisfies(snapshot -> {
                    assertThat(snapshot.task().title()).isEqualTo("Legacy task");
                    assertThat(snapshot.spec().body()).isEqualTo("Frozen legacy spec");
                });

        EventRepository.Persisted persisted = events.persistEvent(
                new EventIngestRequest(
                        "codex",
                        "post-refactor-client",
                        "turn-1",
                        "Observation",
                        "assistant",
                        "New event after migration",
                        "/repo",
                        null,
                        null,
                        null,
                        Map.of("kind", "observation"),
                        Instant.parse("2026-07-20T13:00:00Z")),
                Instant.parse("2026-07-20T13:00:00Z"),
                "Post-refactor session",
                TitleRank.EXPLICIT);
        assertThat(persisted.session().eventCount()).isEqualTo(1);
        assertThat(events.eventsForSession(persisted.session().id(), 10)).singleElement()
                .extracting(AgentEvent::text)
                .isEqualTo("New event after migration");

        var spec = tasks.createSpec("/repo", "New spec", "New frozen body", Map.of(), "planner");
        var task = tasks.enqueueTask(spec.id(), "New task", "codex", 1, "planner");
        assertThat(tasks.findTask(task.snapshot().task().id())).isPresent();
        assertThat(jdbc.queryForObject("PRAGMA journal_mode", String.class)).isEqualToIgnoringCase("wal");
    }
}
