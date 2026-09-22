package dev.nathan.sbaagentic.memory.internal.application;

import static org.assertj.core.api.Assertions.assertThat;

import dev.nathan.sbaagentic.memory.internal.application.port.TextEmbedder;
import dev.nathan.sbaagentic.memory.internal.domain.EmbeddingVector;
import dev.nathan.sbaagentic.recording.AgentEvent;
import dev.nathan.sbaagentic.recording.AgentSession;
import dev.nathan.sbaagentic.recording.EventIngestRequest;
import dev.nathan.sbaagentic.recording.EventRecorder;
import dev.nathan.sbaagentic.recording.IngestResponse;
import dev.nathan.sbaagentic.recording.internal.adapter.out.sqlite.RecordingSqlStore;
import dev.nathan.sbaagentic.summary.SummaryOperations;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(
        properties = {
            "spring.datasource.url=jdbc:sqlite:${java.io.tmpdir}/bb-embedding-indexer-test-${random.uuid}.db",
            "sba.local-ai.enabled=false",
            "sba.summary.backend=local",
            "sba.elasticsearch.enabled=false",
            "sba.ask.embedding-enabled=false",
            "sba.memory.embedding.enabled=false"
        })
class EmbeddingIndexerIntegrationTest {

    @Autowired
    EventRecorder recorder;

    @Autowired
    RecordingSqlStore recordingStore;

    @Autowired
    SummaryOperations summaryOperations;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    CountingTextEmbedder embedder;

    @Autowired
    EmbeddingIndexer indexer;

    @BeforeEach
    void resetDatabase() {
        jdbcTemplate.update("DELETE FROM memory_embeddings");
        jdbcTemplate.update("DELETE FROM agent_events");
        jdbcTemplate.update("DELETE FROM agent_sessions");
        embedder.reset();
    }

    @AfterEach
    void resetEmbedder() {
        embedder.reset();
    }

    @Test
    void recordingDecisionProducesOneEmbeddingRow() {
        recorder.ingest(event(
                "decision-session",
                "Decision",
                "Keep SQLite canonical",
                Map.of("kind", "decision", "decision", "Keep SQLite canonical")));

        assertThat(embeddingCount()).isEqualTo(1);
        assertThat(embedder.calls()).isEqualTo(1);
    }

    @Test
    void recordingPostToolUseProducesNoEmbeddingRow() {
        recorder.ingest(event("tool-session", "PostToolUse", "Ran tests", Map.of()));

        assertThat(embeddingCount()).isZero();
        assertThat(embedder.calls()).isZero();
    }

    @Test
    void throwingEmbedderStillLeavesEventRecorded() {
        embedder.throwing(true);

        IngestResponse response = recorder.ingest(event(
                "throwing-session",
                "Decision",
                "Record before indexing",
                Map.of("kind", "decision", "decision", "Record before indexing")));

        assertThat(embedder.calls()).isEqualTo(1);
        assertThat(recordingStore.eventsForSession(response.sessionId(), 10))
                .extracting(AgentEvent::id)
                .contains(response.eventId());
        assertThat(embeddingCount()).isZero();
    }

    @Test
    void repeatedSameRecordedEventDoesNotReEmbedUnchangedHash() {
        Instant observedAt = Instant.parse("2026-07-28T12:00:00Z");
        AgentSession session = new AgentSession(
                "session-id",
                "codex",
                "duplicate-index",
                "Duplicate index",
                "/repo",
                null,
                observedAt,
                observedAt,
                1,
                null);
        AgentEvent event = new AgentEvent(
                "event-id",
                session.id(),
                "codex",
                session.clientSessionId(),
                null,
                "Decision",
                "agent",
                "Use one vector per target",
                null,
                null,
                null,
                Map.of("kind", "decision", "decision", "Use one vector per target"),
                observedAt);

        assertThat(indexer.indexEvent(event)).isEqualTo(EmbeddingIndexer.IndexOutcome.EMBEDDED);
        assertThat(indexer.indexEvent(event)).isEqualTo(EmbeddingIndexer.IndexOutcome.SKIPPED);

        assertThat(embeddingCount()).isEqualTo(1);
        assertThat(embedder.calls()).isEqualTo(1);
    }

    @Test
    void writtenSessionSummaryProducesSummaryEmbeddingRow() {
        IngestResponse response = recorder.ingest(new EventIngestRequest(
                "codex",
                "summary-embedding-session",
                "turn-1",
                "UserPromptSubmit",
                "user",
                "Summarize this session into a vector.",
                "/tmp/project",
                null,
                null,
                null,
                Map.of("title", "Summary embedding"),
                Instant.parse("2026-07-28T12:00:00Z")));

        summaryOperations.summarize(response.sessionId());

        assertThat(embeddingCount("session_summary")).isEqualTo(1);
        assertThat(embeddingCount("event")).isZero();
        assertThat(embedder.calls()).isEqualTo(1);
    }

    private EventIngestRequest event(
            String clientSessionId, String eventType, String text, Map<String, Object> metadata) {

        return new EventIngestRequest(
                "codex",
                clientSessionId,
                "turn-1",
                eventType,
                "agent",
                text,
                "/tmp/project",
                null,
                null,
                null,
                metadata,
                Instant.parse("2026-07-28T12:00:00Z"));
    }

    private long embeddingCount() {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM memory_embeddings", Long.class);

        return count == null ? 0 : count;
    }

    private long embeddingCount(String targetKind) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM memory_embeddings WHERE target_kind = ?", Long.class, targetKind);

        return count == null ? 0 : count;
    }

    @TestConfiguration
    static class TestConfig {

        @Bean
        @Primary
        CountingTextEmbedder countingTextEmbedder() {

            return new CountingTextEmbedder();
        }
    }

    static class CountingTextEmbedder implements TextEmbedder {

        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicBoolean throwing = new AtomicBoolean();

        @Override
        public EmbeddingVector embedDocument(String text) {

            return embed(text);
        }

        @Override
        public EmbeddingVector embedQuery(String text) {

            return embed(text);
        }

        @Override
        public String documentContentHash(String text) {

            return EmbeddingVector.contentHash(text);
        }

        private EmbeddingVector embed(String text) {
            calls.incrementAndGet();
            if (throwing.get()) {
                throw new TextEmbeddingUnavailable("boom");
            }

            return new EmbeddingVector("test-model", new float[] {1.0f, calls.get(), text.length()});
        }

        @Override
        public boolean available() {

            return true;
        }

        @Override
        public String model() {

            return "test-model";
        }

        @Override
        public int dimensions() {

            return 3;
        }

        void throwing(boolean value) {
            throwing.set(value);
        }

        int calls() {

            return calls.get();
        }

        void reset() {
            calls.set(0);
            throwing.set(false);
        }
    }
}
