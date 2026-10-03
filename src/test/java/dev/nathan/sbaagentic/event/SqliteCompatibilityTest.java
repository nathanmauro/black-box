package dev.nathan.sbaagentic.recording;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nathan.sbaagentic.recording.internal.adapter.out.sqlite.EventFtsIndex;
import dev.nathan.sbaagentic.recording.internal.adapter.out.sqlite.RecordingSqlStore;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

class SqliteCompatibilityTest {

    @Test
    void preRefactorDatabaseMigratesReadsAndAcceptsNewWrites(@TempDir Path tempDir) {
        DriverManagerDataSource dataSource =
                new DriverManagerDataSource("jdbc:sqlite:" + tempDir.resolve("pre-refactor.db"));
        dataSource.setDriverClassName("org.sqlite.JDBC");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        new ResourceDatabasePopulator(new ClassPathResource("contracts/pre-refactor.sqlite.sql")).execute(dataSource);
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(dataSource);

        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        RecordingSqlStore events = new RecordingSqlStore(
                jdbc,
                objectMapper,
                java.time.Clock.systemDefaultZone(),
                new EventFtsIndex(jdbc, java.time.Clock.systemDefaultZone()));
        events.ensureSchema();

        assertThat(events.findSessionById("legacy-session"))
                .get()
                .extracting(
                        session -> session.clientSessionId(),
                        session -> session.title(),
                        session -> session.eventCount())
                .containsExactly("legacy-client", "Legacy session", 1L);
        assertThat(jdbc.queryForObject(
                        "SELECT title_rank FROM agent_sessions WHERE id = 'legacy-session'", Integer.class))
                .isEqualTo(TitleRank.LEGACY);

        dev.nathan.sbaagentic.recording.internal.application.port.RecordingStore.Persisted persisted =
                new org.springframework.transaction.support.TransactionTemplate(
                                new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource))
                        .execute(status -> events.persistEvent(
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
                                TitleRank.EXPLICIT));
        assertThat(persisted.session().eventCount()).isEqualTo(1);
        assertThat(events.eventsForSession(persisted.session().id(), 10))
                .singleElement()
                .extracting(AgentEvent::text)
                .isEqualTo("New event after migration");

        assertThat(jdbc.queryForObject("PRAGMA journal_mode", String.class)).isEqualToIgnoringCase("wal");
    }
}
