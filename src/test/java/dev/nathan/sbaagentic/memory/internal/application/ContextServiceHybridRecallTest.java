package dev.nathan.sbaagentic.memory.internal.application;

import static org.assertj.core.api.Assertions.assertThat;

import dev.nathan.sbaagentic.memory.MemoryEventReader;
import dev.nathan.sbaagentic.memory.MemoryEventReader.RecallCandidate;
import dev.nathan.sbaagentic.memory.MemoryRecallOperations;
import dev.nathan.sbaagentic.memory.MemoryRecallProperties;
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
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
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
        assertThat(recalled.items().getFirst().score()).isNull();
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
        MemoryVectorStore throwingStore = new ThrowingVectorStore();
        ContextService service = new ContextService(reader, availableEmbedder, throwingStore, recallProperties(0.61));

        RecallResult recalled = service.recall("vector outage lexical token", 168, List.of("decision"));

        assertThat(recalled.mode()).isEqualTo("lexical");
        assertThat(eventIds(recalled)).contains(event.id());
        assertThat(recalled.items().getFirst().score()).isNull();
    }

    @Test
    void fusionOrderIsUnchangedByAttachedCosineScores() {
        Instant now = Instant.now();
        AgentEvent lowCosineLexicalFirst =
                decisionEvent("a-low-cosine", "fusion-order", REPO, "phase-a rank probe lexical low cosine", now);
        AgentEvent highCosineLexicalSecond = decisionEvent(
                "b-high-cosine",
                "fusion-order",
                REPO,
                "phase-a rank probe lexical high cosine",
                now.minus(1, ChronoUnit.MINUTES));
        AgentEvent middleCosineLexicalThird = decisionEvent(
                "c-middle-cosine",
                "fusion-order",
                REPO,
                "phase-a rank probe lexical middle cosine",
                now.minus(2, ChronoUnit.MINUTES));
        MemoryEventReader reader = new StaticEventReader(
                List.of(lowCosineLexicalFirst, highCosineLexicalSecond, middleCosineLexicalThird), REPO);
        MemoryVectorStore vectorStore = new ScriptedVectorStore(
                List.of(
                        new MemoryVectorStore.ScoredKey("event:b-high-cosine", 1.0),
                        new MemoryVectorStore.ScoredKey("event:c-middle-cosine", 0.9),
                        new MemoryVectorStore.ScoredKey("event:a-low-cosine", 0.62)),
                Map.of());
        ContextService service =
                new ContextService(reader, new StubTextEmbedder(), vectorStore, recallProperties(0.61));

        RecallResult recalled = service.recall("phase-a rank probe", 168, List.of("decision"), 3);

        assertThat(recalled.mode()).isEqualTo("hybrid");
        assertThat(eventIds(recalled)).containsExactly("b-high-cosine", "a-low-cosine", "c-middle-cosine");
        assertThat(recalled.items().get(1).score())
                .isLessThan(recalled.items().get(2).score());
    }

    @Test
    void lexicalArmItemInHybridModeGetsCosineFromStoredVector() {
        AgentEvent lexical = decisionEvent(
                "lexical-vector-fetch", "vector-fetch", REPO, "phase-a vector fetch lexical token", Instant.now());
        MemoryEventReader reader = new StaticEventReader(List.of(lexical), REPO);
        MemoryVectorStore vectorStore = new ScriptedVectorStore(
                List.of(),
                Map.of("event:lexical-vector-fetch", new EmbeddingVector("stub-hybrid", new float[] {1.0f, 1.0f, 0.0f
                })));
        ContextService service =
                new ContextService(reader, new StubTextEmbedder(), vectorStore, recallProperties(0.61));

        RecallResult recalled = service.recall("phase-a vector fetch", 168, List.of("decision"), 1);

        assertThat(recalled.mode()).isEqualTo("hybrid");
        assertThat(recalled.items()).singleElement().satisfies(item -> {
            assertThat(item.eventId()).isEqualTo("lexical-vector-fetch");
            assertThat(item.score()).isCloseTo(0.7071, org.assertj.core.data.Offset.offset(0.0001));
        });
    }

    @Test
    void lexicalArmItemMissingStoredVectorInHybridModeGetsNullScore() {
        AgentEvent lexical = decisionEvent(
                "lexical-missing-vector",
                "missing-vector",
                REPO,
                "phase-a missing vector lexical token",
                Instant.now());
        MemoryEventReader reader = new StaticEventReader(List.of(lexical), REPO);
        ContextService service = new ContextService(
                reader, new StubTextEmbedder(), new ScriptedVectorStore(List.of(), Map.of()), recallProperties(0.61));

        RecallResult recalled = service.recall("phase-a missing vector", 168, List.of("decision"), 1);

        assertThat(recalled.mode()).isEqualTo("hybrid");
        assertThat(recalled.items()).singleElement().satisfies(item -> {
            assertThat(item.eventId()).isEqualTo("lexical-missing-vector");
            assertThat(item.score()).isNull();
        });
    }

    @Test
    void semanticOnlyHitBelowFloorIsExcludedBeforeFusion() {
        AgentEvent belowFloor = decisionEvent(
                "semantic-below-floor", "floor-excluded", REPO, "semantic only below floor candidate", Instant.now());
        AgentEvent aboveFloor = decisionEvent(
                "semantic-above-floor",
                "floor-retained",
                REPO,
                "semantic only above floor candidate",
                Instant.now().minus(1, ChronoUnit.MINUTES));
        MemoryEventReader reader = new StaticEventReader(List.of(belowFloor, aboveFloor), REPO);
        MemoryVectorStore vectorStore = new ScriptedVectorStore(
                List.of(
                        new MemoryVectorStore.ScoredKey("event:semantic-below-floor", 0.60),
                        new MemoryVectorStore.ScoredKey("event:semantic-above-floor", 0.70)),
                Map.of());
        ContextService service =
                new ContextService(reader, new StubTextEmbedder(), vectorStore, recallProperties(0.61));

        RecallResult recalled = service.recall("vector only admission probe", 168, List.of("decision"), 10);

        assertThat(recalled.mode()).isEqualTo("hybrid");
        assertThat(eventIds(recalled)).containsExactly("semantic-above-floor");
        assertThat(recalled.items().getFirst().score()).isEqualTo(0.70);
    }

    @Test
    void semanticOnlyHitAtFloorIsIncluded() {
        AgentEvent atFloor = decisionEvent(
                "semantic-at-floor", "floor-edge", REPO, "semantic only threshold edge candidate", Instant.now());
        MemoryEventReader reader = new StaticEventReader(List.of(atFloor), REPO);
        MemoryVectorStore vectorStore = new ScriptedVectorStore(
                List.of(new MemoryVectorStore.ScoredKey("event:semantic-at-floor", 0.61)), Map.of());
        ContextService service =
                new ContextService(reader, new StubTextEmbedder(), vectorStore, recallProperties(0.61));

        RecallResult recalled = service.recall("vector only threshold probe", 168, List.of("decision"), 10);

        assertThat(recalled.mode()).isEqualTo("hybrid");
        assertThat(recalled.items()).singleElement().satisfies(item -> {
            assertThat(item.eventId()).isEqualTo("semantic-at-floor");
            assertThat(item.score()).isEqualTo(0.61);
        });
    }

    @Test
    void lexicalHitWithBelowFloorCosineIsRetainedWithRealScore() {
        AgentEvent lexical = decisionEvent(
                "lexical-below-floor", "floor-lexical", REPO, "literal below floor beacon", Instant.now());
        MemoryEventReader reader = new StaticEventReader(List.of(lexical), REPO);
        MemoryVectorStore vectorStore = new ScriptedVectorStore(
                List.of(new MemoryVectorStore.ScoredKey("event:lexical-below-floor", 0.60)),
                Map.of("event:lexical-below-floor", new EmbeddingVector("stub-hybrid", new float[] {0.6f, 0.8f, 0.0f
                })));
        ContextService service =
                new ContextService(reader, new StubTextEmbedder(), vectorStore, recallProperties(0.61));

        RecallResult recalled = service.recall("literal below floor beacon", 168, List.of("decision"), 10);

        assertThat(recalled.mode()).isEqualTo("hybrid");
        assertThat(recalled.items()).singleElement().satisfies(item -> {
            assertThat(item.eventId()).isEqualTo("lexical-below-floor");
            assertThat(item.score()).isCloseTo(0.6, org.assertj.core.data.Offset.offset(0.0001));
        });
    }

    @Test
    void noLexicalMatchesAndAllSemanticHitsBelowFloorReturnsHonestZero() {
        AgentEvent first = decisionEvent(
                "semantic-low-one", "floor-zero-one", REPO, "unrelated low semantic candidate one", Instant.now());
        AgentEvent second = decisionEvent(
                "semantic-low-two",
                "floor-zero-two",
                REPO,
                "unrelated low semantic candidate two",
                Instant.now().minus(1, ChronoUnit.MINUTES));
        MemoryEventReader reader = new StaticEventReader(List.of(first, second), REPO);
        MemoryVectorStore vectorStore = new ScriptedVectorStore(
                List.of(
                        new MemoryVectorStore.ScoredKey("event:semantic-low-one", 0.60),
                        new MemoryVectorStore.ScoredKey("event:semantic-low-two", 0.20)),
                Map.of());
        ContextService service =
                new ContextService(reader, new StubTextEmbedder(), vectorStore, recallProperties(0.61));

        RecallResult recalled = service.recall("no matching lexical words here", 168, List.of("decision"), 10);

        assertThat(recalled.mode()).isEqualTo("hybrid");
        assertThat(recalled.count()).isZero();
        assertThat(recalled.items()).isEmpty();
    }

    @Test
    void zeroRelevanceFloorDisablesSemanticAdmissionFilter() {
        AgentEvent belowFloor = decisionEvent(
                "semantic-floor-disabled",
                "floor-disabled",
                REPO,
                "semantic only disabled floor candidate",
                Instant.now());
        MemoryEventReader reader = new StaticEventReader(List.of(belowFloor), REPO);
        MemoryVectorStore vectorStore = new ScriptedVectorStore(
                List.of(new MemoryVectorStore.ScoredKey("event:semantic-floor-disabled", 0.20)), Map.of());
        ContextService service = new ContextService(reader, new StubTextEmbedder(), vectorStore, recallProperties(0.0));

        RecallResult recalled = service.recall("vector only disabled floor probe", 168, List.of("decision"), 10);

        assertThat(recalled.mode()).isEqualTo("hybrid");
        assertThat(recalled.items()).singleElement().satisfies(item -> {
            assertThat(item.eventId()).isEqualTo("semantic-floor-disabled");
            assertThat(item.score()).isEqualTo(0.20);
        });
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
            assertThat(item.score()).isNull();
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
        assertThat(byRepoPath.items().getFirst().score()).isNull();
    }

    @Test
    void blankScopeStaysLexicalWithNullScores() {
        IngestResponse captured = captureOperations.captureDecision(new CaptureDecisionRequest(
                "codex",
                "hybrid-blank-scope",
                REPO,
                "Blank recall scope stays lexical",
                "Recent intent is ordered by time when no query vector exists.",
                List.of(),
                0.8,
                List.of()));

        RecallResult recalled = recallOperations.recall("", 168, List.of("decision"));

        assertThat(recalled.mode()).isEqualTo("lexical");
        assertThat(recalled.items()).singleElement().satisfies(item -> {
            assertThat(item.eventId()).isEqualTo(captured.eventId());
            assertThat(item.score()).isNull();
        });
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
            if (!available.get()) {
                throw new TextEmbeddingUnavailable("stub embedder unavailable");
            }

            return new EmbeddingVector(MODEL, documentVectorFor(text));
        }

        @Override
        public EmbeddingVector embedQuery(String text) {
            if (!available.get()) {
                throw new TextEmbeddingUnavailable("stub embedder unavailable");
            }

            return new EmbeddingVector(MODEL, queryVectorFor(text));
        }

        @Override
        public String documentContentHash(String text) {

            return EmbeddingVector.contentHash(text);
        }

        private float[] queryVectorFor(String text) {
            String normalized = text == null ? "" : text.toLowerCase(Locale.ROOT);
            if (normalized.contains("phase-a rank probe")
                    || normalized.contains("phase-a vector fetch")
                    || normalized.contains("phase-a missing vector")
                    || normalized.contains("literal below floor beacon")) {

                return new float[] {1.0f, 0.0f, 0.0f};
            }

            return documentVectorFor(text);
        }

        private float[] documentVectorFor(String text) {
            String normalized = text == null ? "" : text.toLowerCase(Locale.ROOT);
            if (normalized.contains("phase-a rank probe lexical low cosine")) {

                return new float[] {0.62f, 0.7846018f, 0.0f};
            }
            if (normalized.contains("phase-a rank probe lexical high cosine")) {

                return new float[] {1.0f, 0.0f, 0.0f};
            }
            if (normalized.contains("phase-a rank probe lexical middle cosine")) {

                return new float[] {0.9f, 0.4358899f, 0.0f};
            }
            if (normalized.contains("shared-cache")
                    || normalized.contains("sqlite locking")
                    || normalized.contains("deadlock")
                    || normalized.contains("sqlite_locked")) {

                return new float[] {1.0f, 0.0f, 0.0f};
            }
            if (normalized.contains("exact-token") || normalized.contains("lexical fallback beacon")) {

                return new float[] {0.0f, 1.0f, 0.0f};
            }

            return new float[] {0.0f, 0.0f, 1.0f};
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
    }

    private static MemoryRecallProperties recallProperties(double relevanceFloor) {
        MemoryRecallProperties properties = new MemoryRecallProperties();
        properties.setRelevanceFloor(relevanceFloor);

        return properties;
    }

    private static AgentEvent decisionEvent(
            String id, String clientSessionId, String repo, String text, Instant observedAt) {

        return new AgentEvent(
                id,
                "session-" + clientSessionId,
                "codex",
                clientSessionId,
                null,
                "Decision",
                "assistant",
                text,
                null,
                null,
                null,
                Map.of(
                        "kind", "decision",
                        "repo", repo,
                        "decision", text),
                observedAt);
    }

    private record ScriptedVectorStore(
            List<MemoryVectorStore.ScoredKey> scoredKeys, Map<String, EmbeddingVector> vectors)
            implements MemoryVectorStore {

        @Override
        public List<MemoryVectorStore.ScoredKey> knn(
                EmbeddingVector query, int k, java.util.function.Predicate<String> keyFilter) {

            return scoredKeys.stream()
                    .filter(scored -> keyFilter.test(scored.key()))
                    .limit(Math.max(0, k))
                    .toList();
        }

        @Override
        public Map<String, EmbeddingVector> fetchVectors(Collection<String> keys, String model, int dimensions) {
            Map<String, EmbeddingVector> matches = new LinkedHashMap<>();
            for (String key : keys) {
                EmbeddingVector vector = vectors.get(key);
                if (vector != null && vector.model().equals(model) && vector.values().length == dimensions) {
                    matches.put(key, vector);
                }
            }

            return matches;
        }
    }

    private static class ThrowingVectorStore implements MemoryVectorStore {

        @Override
        public List<MemoryVectorStore.ScoredKey> knn(
                EmbeddingVector query, int k, java.util.function.Predicate<String> keyFilter) {
            throw new IllegalStateException("vector store down");
        }

        @Override
        public Map<String, EmbeddingVector> fetchVectors(Collection<String> keys, String model, int dimensions) {

            return Map.of();
        }
    }

    private record StaticEventReader(List<AgentEvent> events, String cwd) implements MemoryEventReader {

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

            return events.stream()
                    .filter(event -> eventTypes.contains(event.eventType()))
                    .filter(event -> !event.observedAt().isBefore(since))
                    .filter(event -> scopeLike == null || lexicalMatch(event, scopeLike))
                    .sorted((left, right) -> right.observedAt().compareTo(left.observedAt()))
                    .limit(limit)
                    .toList();
        }

        @Override
        public List<RecallCandidate> recallCandidates(List<String> eventTypes, Instant since) {

            return events.stream()
                    .filter(event -> eventTypes.contains(event.eventType()))
                    .filter(event -> !event.observedAt().isBefore(since))
                    .sorted((left, right) -> right.observedAt().compareTo(left.observedAt()))
                    .map(event -> new RecallCandidate(event, cwd))
                    .toList();
        }

        private static boolean lexicalMatch(AgentEvent event, String scopeLike) {
            String needle = scopeLike.replace("%", "").toLowerCase(Locale.ROOT);

            return event.id().toLowerCase(Locale.ROOT).contains(needle)
                    || event.text().toLowerCase(Locale.ROOT).contains(needle);
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
