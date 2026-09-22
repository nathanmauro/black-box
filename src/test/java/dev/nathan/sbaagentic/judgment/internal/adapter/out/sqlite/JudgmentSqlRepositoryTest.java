package dev.nathan.sbaagentic.judgment.internal.adapter.out.sqlite;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import dev.nathan.sbaagentic.judgment.internal.application.Judgment;
import dev.nathan.sbaagentic.judgment.internal.domain.Beat;
import dev.nathan.sbaagentic.judgment.internal.domain.BeatEvent;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

class JudgmentSqlRepositoryTest {

    @Test
    void savesOneJudgmentRowPerBeatEventAndReadsNewestFirst() {
        Fixture fixture = fixture();
        insertSessionAndEvents(fixture.jdbc());
        Beat beat = new Beat(
                "beat-1",
                "session-1",
                Instant.parse("2026-09-21T12:00:00Z"),
                Instant.parse("2026-09-21T12:00:01Z"),
                List.of(event("event-1", "Decision"), event("event-2", "PostToolUse")),
                List.of("Decision: choose", "exec(mvn test) → passed"),
                "Decision: choose\nexec(mvn test) → passed");
        Judgment judgment = new Judgment(
                "building",
                1.0,
                0.5,
                0.0,
                Map.of("session-2", 0.7),
                "jev",
                "jev-latest",
                "orbit-jev-v1",
                JsonNodeFactory.instance.objectNode().put("phase", "building"),
                Instant.parse("2026-09-21T12:00:02Z"),
                44L);

        fixture.repository().saveForBeat(beat, judgment);

        assertThat(fixture.repository().findByEventId("event-1")).get().satisfies(row -> {
            assertThat(row.beatId()).isEqualTo("beat-1");
            assertThat(row.version()).isEqualTo("orbit-jev-v1");
            assertThat(row.answers().path("phase").asText()).isEqualTo("building");
            assertThat(row.answers().path("human").asDouble(-1)).isZero();
            assertThat(row.answers().path("kin").path("session-2").asDouble()).isEqualTo(0.7);
            assertThat(row.answers().path("raw").path("phase").asText()).isEqualTo("building");
        });
        assertThat(fixture.repository().findForSession("session-1", 10))
                .extracting(row -> row.eventId())
                .containsExactly("event-2", "event-1");
    }

    private static Fixture fixture() {
        Path database =
                Path.of(System.getProperty("java.io.tmpdir"), "bb-judgment-sql-test-" + UUID.randomUUID() + ".db");
        DriverManagerDataSource dataSource = new DriverManagerDataSource("jdbc:sqlite:" + database);
        dataSource.setDriverClassName("org.sqlite.JDBC");
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(dataSource);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        return new Fixture(jdbc, new JudgmentSqlRepository(jdbc, new ObjectMapper()));
    }

    private static void insertSessionAndEvents(JdbcTemplate jdbc) {
        jdbc.update("""
                INSERT INTO agent_sessions (
                    id, source, client_session_id, title, title_rank, cwd, started_at, last_seen_at, event_count
                )
                VALUES ('session-1', 'codex', 'client-1', 'Orbit', 5, '/repo',
                        '2026-09-21T12:00:00Z', '2026-09-21T12:00:01Z', 2)
                """);
        for (String id : List.of("event-1", "event-2")) {
            jdbc.update("""
                    INSERT INTO agent_events (
                        id, session_id, source, client_session_id, event_type, role, text, observed_at
                    )
                    VALUES (?, 'session-1', 'codex', 'client-1', 'Decision', 'assistant', 'text',
                            '2026-09-21T12:00:00Z')
                    """, id);
        }
    }

    private static BeatEvent event(String id, String type) {

        return new BeatEvent(
                id,
                "session-1",
                type,
                "assistant",
                "text",
                null,
                null,
                null,
                Map.of(),
                Instant.parse("2026-09-21T12:00:00Z"));
    }

    private record Fixture(JdbcTemplate jdbc, JudgmentSqlRepository repository) {}
}
