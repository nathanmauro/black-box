package dev.nathan.sbaagentic.memory.internal.adapter.out.sqlite;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nathan.sbaagentic.memory.internal.application.port.EmbeddingSourceReader.EmbeddingSource;
import dev.nathan.sbaagentic.memory.internal.domain.EmbeddableText;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

class EmbeddingSourceSqlReaderTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void normalizesStructuredEventTypesLikeTheLiveIndexer() {
        Fixture fixture = fixture();
        fixture.insertSession("session-1");
        fixture.insertEvent("event-1", "session-1", "decision", "lowercase", Map.of());
        fixture.insertEvent("event-2", "session-1", "DECISION", "uppercase", Map.of());
        fixture.insertEvent("event-3", "session-1", "hand_off", "snake case", Map.of());
        fixture.insertEvent("event-4", "session-1", "PostToolUse", "tool noise", Map.of());

        assertThat(fixture.reader().nextBatch(null, null, 10))
                .extracting(EmbeddingSource::targetId)
                .containsExactly("event-1", "event-2", "event-3");
    }

    @Test
    void includesMetadataOnlyStructuredEvents() {
        Fixture fixture = fixture();
        fixture.insertSession("session-1");
        fixture.insertEvent(
                "event-1",
                "session-1",
                "Decision",
                "  ",
                Map.of("decision", "Use metadata text", "rationale", "raw text is empty"));

        EmbeddingSource source = fixture.reader().nextBatch(null, null, 10).getFirst();

        assertThat(source.targetId()).isEqualTo("event-1");
        assertThat(EmbeddableText.forEvent(source.eventType(), source.text(), source.metadata()))
                .isEqualTo("Use metadata text raw text is empty");
    }

    private static Fixture fixture() {
        Path database = Path.of(
                System.getProperty("java.io.tmpdir"), "bb-embedding-source-reader-test-" + UUID.randomUUID() + ".db");
        database.toFile().deleteOnExit();
        DriverManagerDataSource dataSource = new DriverManagerDataSource("jdbc:sqlite:" + database);
        dataSource.setDriverClassName("org.sqlite.JDBC");
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(dataSource);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

        return new Fixture(jdbcTemplate, new EmbeddingSourceSqlReader(jdbcTemplate, OBJECT_MAPPER));
    }

    private record Fixture(JdbcTemplate jdbcTemplate, EmbeddingSourceSqlReader reader) {

        void insertSession(String id) {
            jdbcTemplate.update("""
                    INSERT INTO agent_sessions (
                        id, source, client_session_id, title, cwd, started_at, last_seen_at, event_count
                    )
                    VALUES (
                        ?, 'codex', ?, ?, '/repo',
                        '2026-07-28T12:00:00Z', '2026-07-28T12:00:00Z', 0
                    )
                    """, id, id, id);
        }

        void insertEvent(String id, String sessionId, String eventType, String text, Map<String, Object> metadata) {
            jdbcTemplate.update("""
                    INSERT INTO agent_events (
                        id, session_id, source, client_session_id, event_type, role, text, metadata_json, observed_at
                    )
                    VALUES (?, ?, 'codex', ?, ?, 'agent', ?, ?, '2026-07-28T12:00:00Z')
                    """, id, sessionId, sessionId, eventType, text, json(metadata));
        }
    }

    private static String json(Map<String, Object> metadata) {
        try {

            return OBJECT_MAPPER.writeValueAsString(metadata);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException(ex);
        }
    }
}
