package dev.nathan.sbaagentic.memory.internal.application;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import dev.nathan.sbaagentic.memory.MemoryEventReader;
import dev.nathan.sbaagentic.memory.MemoryEventReader.RecallCandidate;
import dev.nathan.sbaagentic.memory.MemoryRecallOperations;
import dev.nathan.sbaagentic.memory.RecallResult;
import dev.nathan.sbaagentic.memory.RecalledItem;
import dev.nathan.sbaagentic.memory.internal.application.port.MemoryVectorStore;
import dev.nathan.sbaagentic.memory.internal.application.port.TextEmbedder;
import dev.nathan.sbaagentic.memory.internal.domain.EmbeddingVector;
import dev.nathan.sbaagentic.recording.AgentEvent;
import dev.nathan.sbaagentic.recording.CaptureDecisionRequest;
import dev.nathan.sbaagentic.recording.EventIngestRequest;
import dev.nathan.sbaagentic.recording.EventRecorder;
import dev.nathan.sbaagentic.recording.IngestResponse;
import dev.nathan.sbaagentic.recording.RecordingCaptureOperations;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite:${java.io.tmpdir}/bb-hybrid-recall-test-${random.uuid}.db",
        "sba.local-ai.enabled=false",
        "sba.summary.backend=local",
        "sba.elasticsearch.enabled=false",
        "sba.ask.embedding-enabled=false",
        "sba.memory.embedding.enabled=false"
})
class ContextServiceHybridRecallTest {

    private static final String REPO = "/tmp/hybrid-recall";

    @Autowired
    RecordingCaptureOperations captureOperations;

    @Autowired
    EventRecorder recorder;

    @Autowired
    MemoryRecallOperations recallOperations;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    StubTextEmbedder embedder;

    @BeforeEach
    void resetDatabase() {
        jdbcTemplate.update("DELETE FROM memory_embeddings");
        jdbcTemplate.update("DELETE FROM agent_events");
        jdbcTemplate.update("DELETE FROM agent_sessions");
        embedder.available(true);
    }

    @Test
    void semanticRecallFindsParaphrasedDecisionThatLexicalOnlyMisses() {
        IngestResponse captured = captureOperations.captureDecision(new CaptureDecisionRequest(
                "codex",
                "hybrid-money",
                REPO,
                "Move Spring test databases off shared-cache memory onto temp files",
                "Shared-cache SQLite locking can surface SQLITE_LOCKED during concurrent test writers.",
                List.of("Keep cache=shared memory databases"),
                0.92,
                List.of("Claude still needs to run the suite")));

        RecallResult hybrid = recallOperations.recall("why do the tests deadlock", 168, List.of("decision"));

        assertThat(hybrid.mode()).isEqualTo("hybrid");
        assertThat(eventIds(hybrid)).contains(captured.eventId());
        RecalledItem semanticMatch = hybrid.items().stream()
                .filter(item -> item.eventId().equals(captured.eventId()))
                .findFirst()
                .orElseThrow();
        assertThat(semanticMatch.score()).isPositive();

        embedder.available(false);
        RecallResult lexicalOnly = recallOperations.recall("why do the tests deadlock", 168, List.of("decision"));

        assertThat(lexicalOnly.mode()).isEqualTo("lexical");
        assertThat(eventIds(lexicalOnly)).doesNotContain(captured.eventId());
    }

    @Test
    void embedderUnavailableReturnsLexicalModeWithLexicalResults() {
        IngestResponse captured = captureOperations.captureDecision(new CaptureDecisionRequest(
                "codex",
                "hybrid-degraded",
                REPO,
                "Keep the lexical fallback beacon available",
                "The exact fallback phrase must still work when semantic recall is offline.",
                List.of(),
                0.7,
                List.of()));

        embedder.available(false);
        RecallResult recalled = recallOperations.recall("lexical fallback beacon", 168, List.of("decision"));

        assertThat(recalled.mode()).isEqualTo("lexical");
        assertThat(eventIds(recalled)).contains(captured.eventId());
        assertThat(recalled.items().getFirst().score()).isPositive();
    }

    @Test
    void vectorStoreUnavailableReturnsLexicalModeWithLexicalResults() {
        AgentEvent event = new AgentEvent(
                "event-vector-down",
                "session-vector-down",
                "codex",
                "vector-down",
                null,
                "Decision",
                "assistant",
                "Keep vector outage lexical token recallable",
                null,
                null,
                null,
                Map.of(
                        "kind", "decision",
                        "repo", REPO,
                        "decision", "Keep vector outage lexical token recallable"),
                Instant.now());
        MemoryEventReader reader = new SingleEventReader(event, REPO);
        StubTextEmbedder availableEmbedder = new StubTextEmbedder();
        MemoryVectorStore throwingStore = (query, k, keyFilter) -> {
            throw new IllegalStateException("vector store down");
        };
        ContextService service = new ContextService(reader, availableEmbedder, throwingStore);

        RecallResult recalled = service.recall("vector outage lexical token", 168, List.of("decision"));

        assertThat(recalled.mode()).isEqualTo("lexical");
        assertThat(eventIds(recalled)).contains(event.id());
    }

    @Test
    void exactTokenAndEventIdLookupsStillWork() {
        IngestResponse captured = captureOperations.captureDecision(new CaptureDecisionRequest(
                "codex",
                "hybrid-exact",
                REPO,
                "Preserve the exact-token recall path",
                "The exact-token recall path is still lexical and direct event ids are still keys.",
                List.of(),
                0.8,
                List.of()));

        RecallResult byToken = recallOperations.recall("exact-token recall path", 168, List.of("decision"));
        RecallResult byId = recallOperations.recall(captured.eventId(), 168, List.of("decision"));

        // A topic-shaped scope engages semantic recall...
        assertThat(byToken.mode()).isEqualTo("hybrid");
        assertThat(eventIds(byToken)).contains(captured.eventId());
        // ...but a raw event id is a LOCATION, not a subject. Embedding it would rank candidates
        // by similarity to a UUID, so recall stays purely lexical and the direct lookup is exact.
        assertThat(byId.mode()).isEqualTo("lexical");
        assertThat(byId.items()).singleElement().satisfies(item -> {
            assertThat(item.eventId()).isEqualTo(captured.eventId());
            assertThat(item.kind()).isEqualTo("decision");
        });
    }

    @Test
    void repoPathScopeStaysLexicalSoRecencyOrderingIsNotPerturbed() {
        IngestResponse captured = captureOperations.captureDecision(new CaptureDecisionRequest(
                "codex",
                "hybrid-path-scope",
                REPO,
                "Recall by repo path stays on the lexical path",
                "A filesystem path is a location, not a subject.",
                List.of(),
                0.8,
                List.of()));

        RecallResult byRepoPath = recallOperations.recall(REPO, 168, List.of("decision"));

        // "What was decided in this repo lately" is the dominant use of recall and is answered by
        // recency. Embedding the repo path would rank in-repo events by similarity to a path
        // string and fusing that in would perturb that ordering for no gain.
        assertThat(byRepoPath.mode()).isEqualTo("lexical");
        assertThat(eventIds(byRepoPath)).contains(captured.eventId());
    }

    @Test
    void semanticCandidatesHonorKindsScopeAndWithinHoursFilters() {
        IngestResponse inScope = captureOperations.captureDecision(new CaptureDecisionRequest(
                "codex",
                "hybrid-scope-a",
                "/tmp/hybrid-scope-a",
                "Move Spring test databases off shared-cache memory onto temp files",
                "Shared-cache SQLite locking can surface SQLITE_LOCKED during concurrent test writers.",
                List.of(),
                0.9,
                List.of()));
        IngestResponse outOfScope = captureOperations.captureDecision(new CaptureDecisionRequest(
                "codex",
                "hybrid-scope-b",
                "/tmp/hybrid-scope-b",
                "Move Spring test databases off shared-cache memory onto temp files",
                "Shared-cache SQLite locking can surface SQLITE_LOCKED during concurrent test writers.",
                List.of(),
                0.9,
                List.of()));
        IngestResponse old = recorder.ingest(new EventIngestRequest(
                "codex",
                "hybrid-old",
                null,
                "Decision",
                "assistant",
                "Move Spring test databases off shared-cache memory onto temp files",
                "/tmp/hybrid-scope-a",
                null,
                null,
                null,
                Map.of(
                        "kind", "decision",
                        "repo", "/tmp/hybrid-scope-a",
                        "decision", "Move Spring test databases off shared-cache memory onto temp files",
                        "rationale", "Shared-cache SQLite locking can surface SQLITE_LOCKED."),
                Instant.now().minus(10, ChronoUnit.DAYS)));

        RecallResult wrongKind = recallOperations.recall("why do the tests deadlock", 168, List.of("handoff"));
        RecallResult scoped = recallOperations.recall("/tmp/hybrid-scope-a", 168, List.of("decision"));
        RecallResult recent = recallOperations.recall("why do the tests deadlock", 1, List.of("decision"));

        assertThat(wrongKind.mode()).isEqualTo("hybrid");
        assertThat(eventIds(wrongKind)).doesNotContain(inScope.eventId(), outOfScope.eventId(), old.eventId());
        assertThat(eventIds(scoped)).contains(inScope.eventId());
        assertThat(eventIds(scoped)).doesNotContain(outOfScope.eventId());
        assertThat(eventIds(recent)).contains(inScope.eventId(), outOfScope.eventId());
        assertThat(eventIds(recent)).doesNotContain(old.eventId());
    }

    @Test
    void bareRepoNameScopesSemanticCandidates() {
        IngestResponse inScope = captureOperations.captureDecision(new CaptureDecisionRequest(
                "codex",
                "hybrid-bare-scope-a",
                "/repos/sba-agentic",
                "Move Spring test databases off shared-cache memory onto temp files",
                "Shared-cache SQLite locking can surface SQLITE_LOCKED during concurrent test writers.",
                List.of(),
                0.9,
                List.of()));
        IngestResponse outOfScope = captureOperations.captureDecision(new CaptureDecisionRequest(
                "codex",
                "hybrid-bare-scope-b",
                "/repos/cockpit",
                "Move Spring test databases off shared-cache memory onto temp files",
                "Shared-cache SQLite locking can surface SQLITE_LOCKED during concurrent test writers.",
                List.of(),
                0.9,
                List.of()));

        RecallResult recalled = recallOperations.recall("sba-agentic", 168, List.of("decision"));

        assertThat(recalled.mode()).isEqualTo("hybrid");
        assertThat(eventIds(recalled)).contains(inScope.eventId());
        assertThat(eventIds(recalled)).doesNotContain(outOfScope.eventId());
    }

    private static List<String> eventIds(RecallResult result) {
        return result.items().stream().map(RecalledItem::eventId).toList();
    }

    @TestConfiguration
    static class TestConfig {

        @Bean
        @Primary
        StubTextEmbedder stubTextEmbedder() {
            return new StubTextEmbedder();
        }
    }

    static class StubTextEmbedder implements TextEmbedder {

        private static final String MODEL = "stub-hybrid";
        private final AtomicBoolean available = new AtomicBoolean(true);

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
            if (!available.get()) {
                throw new TextEmbeddingUnavailable("stub embedder unavailable");
            }
            return new EmbeddingVector(MODEL, vectorFor(text));
        }

        @Override
        public boolean available() {
            return available.get();
        }

        @Override
        public String model() {
            return MODEL;
        }

        @Override
        public int dimensions() {
            return 3;
        }

        void available(boolean nextAvailable) {
            available.set(nextAvailable);
        }

        private static float[] vectorFor(String text) {
            String normalized = text == null ? "" : text.toLowerCase(Locale.ROOT);
            if (normalized.contains("shared-cache")
                    || normalized.contains("sqlite locking")
                    || normalized.contains("deadlock")
                    || normalized.contains("sqlite_locked")) {
                return new float[] { 1.0f, 0.0f, 0.0f };
            }
            if (normalized.contains("exact-token") || normalized.contains("lexical fallback beacon")) {
                return new float[] { 0.0f, 1.0f, 0.0f };
            }
            return new float[] { 0.0f, 0.0f, 1.0f };
        }
    }

    private record SingleEventReader(AgentEvent event, String cwd) implements MemoryEventReader {

        @Override
        public List<AgentEvent> searchEvents(String query, List<String> projectScopes, int limit) {
            return List.of();
        }

        @Override
        public List<String> distinctFieldValues(String field, String prefix, int limit) {
            return List.of();
        }

        @Override
        public List<AgentEvent> recall(List<String> eventTypes, String scopeLike, Instant since, int limit) {
            if (!eventTypes.contains(event.eventType()) || event.observedAt().isBefore(since)) {
                return List.of();
            }
            if (scopeLike == null || lexicalMatch(scopeLike)) {
                return List.of(event);
            }
            return List.of();
        }

        @Override
        public List<RecallCandidate> recallCandidates(List<String> eventTypes, Instant since) {
            if (!eventTypes.contains(event.eventType()) || event.observedAt().isBefore(since)) {
                return List.of();
            }
            return List.of(new RecallCandidate(event, cwd));
        }

        private boolean lexicalMatch(String scopeLike) {
            String needle = scopeLike.replace("%", "").toLowerCase(Locale.ROOT);
            return event.id().toLowerCase(Locale.ROOT).contains(needle)
                    || cwd.toLowerCase(Locale.ROOT).contains(needle)
                    || event.text().toLowerCase(Locale.ROOT).contains(needle);
        }
    }
}
