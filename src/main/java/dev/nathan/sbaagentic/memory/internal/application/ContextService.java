package dev.nathan.sbaagentic.memory.internal.application;

import dev.nathan.sbaagentic.memory.MemoryEventReader;
import dev.nathan.sbaagentic.memory.MemoryEventReader.RecallCandidate;
import dev.nathan.sbaagentic.memory.MemoryHit;
import dev.nathan.sbaagentic.memory.MemoryRecallOperations;
import dev.nathan.sbaagentic.memory.MemoryRecallProperties;
import dev.nathan.sbaagentic.memory.RecallRequestContext;
import dev.nathan.sbaagentic.memory.RecallResult;
import dev.nathan.sbaagentic.memory.RecalledItem;
import dev.nathan.sbaagentic.memory.ReciprocalRankFusion;
import dev.nathan.sbaagentic.memory.internal.application.RecallTelemetry.Sample;
import dev.nathan.sbaagentic.memory.internal.application.port.MemoryVectorStore;
import dev.nathan.sbaagentic.memory.internal.application.port.MemoryVectorStore.ScoredKey;
import dev.nathan.sbaagentic.memory.internal.application.port.TextEmbedder;
import dev.nathan.sbaagentic.memory.internal.domain.EmbeddingVector;
import dev.nathan.sbaagentic.recording.AgentEvent;
import dev.nathan.sbaagentic.recording.Titles;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * The write+query loop that is Black Box's reason to exist. Agents write structured <em>intent</em>
 * — decisions, handoffs, observations, and projections — into the recorder, and any later agent
 * (or a future self) reads that intent back, scoped by repo or topic and bounded in time, at runtime
 * and entirely on localhost.
 *
 * <p>Decisions and handoffs are persisted as first-class {@link AgentEvent}s so they live on the
 * same timeline as prompts and tool calls; their structure rides in the event metadata, and
 * {@link #recall} projects that metadata back into the typed {@link RecalledItem} an agent needs.
 * The event log is the source of truth; recall is a projection over it.
 */
@Service
public class ContextService implements MemoryRecallOperations {

    public static final String KIND_DECISION = "decision";
    public static final String KIND_HANDOFF = "handoff";
    public static final String KIND_OBSERVATION = "observation";
    public static final String KIND_PROJECTION = "projection";

    private static final Map<String, String> EVENT_TYPE_BY_KIND = Map.of(
            KIND_DECISION, "Decision",
            KIND_HANDOFF, "Handoff",
            KIND_OBSERVATION, "Observation",
            KIND_PROJECTION, "Projection");

    private static final List<String> DEFAULT_RECALL_KINDS = List.of(KIND_DECISION, KIND_HANDOFF);
    private static final int DEFAULT_WITHIN_HOURS = 168;
    private static final int MAX_WITHIN_HOURS = 24 * 365;
    private static final int RECALL_LIMIT = 50;
    private static final int DEFAULT_RECALL_ITEMS = 10;
    private static final String VECTOR_EVENT_PREFIX = "event:";

    private final MemoryEventReader repository;
    private final TextEmbedder embedder;
    private final MemoryVectorStore vectorStore;
    private final MemoryRecallProperties recallProperties;
    private final RecallTelemetry telemetry;

    public ContextService(
            MemoryEventReader repository,
            TextEmbedder embedder,
            MemoryVectorStore vectorStore,
            MemoryRecallProperties recallProperties) {
        this(repository, embedder, vectorStore, recallProperties, RecallTelemetry.noop());
    }

    @Autowired
    public ContextService(
            MemoryEventReader repository,
            TextEmbedder embedder,
            MemoryVectorStore vectorStore,
            MemoryRecallProperties recallProperties,
            RecallTelemetry telemetry) {
        this.repository = repository;
        this.embedder = embedder;
        this.vectorStore = vectorStore;
        this.recallProperties = recallProperties;
        this.telemetry = telemetry;
    }

    /**
     * Reads prior intent back out. {@code scope} is matched against both the session's working
     * directory (repo) and the captured text, so an agent can recall by where it is working or by
     * what it is working on. A blank scope returns the most recent intent across all repos.
     */
    @Override
    public RecallResult recall(String scope, int withinHours, List<String> kinds, Integer limit) {
        if (RecallRequestContext.current() == null) {
            try (var ignored = RecallRequestContext.open("internal", null, null, null)) {

                return measuredRecall(scope, withinHours, kinds, limit);
            }
        }

        return measuredRecall(scope, withinHours, kinds, limit);
    }

    private RecallResult measuredRecall(String scope, int withinHours, List<String> kinds, Integer limit) {
        String category = scope == null || scope.isBlank() ? "blank" : pathOrIdScope(scope) ? "path_or_id" : "topic";
        Sample sample = new Sample(RecallRequestContext.current(), category);
        sample.relevanceFloor = recallProperties.getRelevanceFloor();
        try {
            RecallResult result = recall(scope, withinHours, kinds, limit, sample);
            sample.outcome = "success";
            sample.resultCount = result.count();

            return result;
        } catch (RuntimeException ex) {
            sample.errorCategory = "recall_error";
            throw ex;
        } finally {
            sample.durationNanos = System.nanoTime() - sample.started;
            try {
                telemetry.complete(sample);
            } catch (RuntimeException ignored) {
            }
        }
    }

    private RecallResult recall(String scope, int withinHours, List<String> kinds, Integer limit, Sample sample) {
        int resolvedLimit = limit == null || limit <= 0 ? DEFAULT_RECALL_ITEMS : Math.min(limit, RECALL_LIMIT);
        List<String> resolvedKinds = resolveKinds(kinds);
        List<String> eventTypes =
                resolvedKinds.stream().map(EVENT_TYPE_BY_KIND::get).toList();
        int hours = withinHours <= 0 ? DEFAULT_WITHIN_HOURS : Math.min(withinHours, MAX_WITHIN_HOURS);
        Instant since = Instant.now().minus(hours, ChronoUnit.HOURS);
        String trimmedScope = scope == null ? null : scope.strip();
        String scopeLike = (trimmedScope == null || trimmedScope.isEmpty())
                ? null
                : "%" + trimmedScope.toLowerCase(Locale.ROOT) + "%";

        long lexicalStarted = System.nanoTime();
        List<AgentEvent> lexicalEvents;
        try {
            lexicalEvents = repository.recall(eventTypes, scopeLike, since, RECALL_LIMIT);
            sample.lexicalCandidates = lexicalEvents.size();
        } finally {
            sample.lexicalNanos = System.nanoTime() - lexicalStarted;
        }
        Map<String, AgentEvent> eventsById = new LinkedHashMap<>();
        List<MemoryHit> lexicalHits = lexicalEvents.stream()
                .peek(event -> eventsById.putIfAbsent(event.id(), event))
                .map(event -> toMemoryHit(event, 0.0))
                .toList();

        SemanticRecall semantic = semanticRecall(trimmedScope, eventTypes, since, eventsById, sample);
        List<MemoryHit> rankedHits = semantic.available()
                ? ReciprocalRankFusion.fuse(lexicalHits, semantic.hits(), RECALL_LIMIT)
                : ReciprocalRankFusion.fuse(lexicalHits, List.of(), RECALL_LIMIT);
        String mode = semantic.available() ? "hybrid" : "lexical";
        sample.mode = mode;

        // Fusion still ranks the full RECALL_LIMIT candidate pool; only the returned page is
        // bounded. Narrowing the pool instead would change which items win, not just how many.
        List<MemoryHit> returnedHits = rankedHits.stream()
                .filter(hit -> hit != null && eventsById.containsKey(hit.id()))
                .limit(resolvedLimit)
                .toList();
        var semanticIds = semantic.hits().stream().map(MemoryHit::id).collect(java.util.stream.Collectors.toSet());
        sample.semanticReturned = (int) returnedHits.stream()
                .filter(hit -> semanticIds.contains(hit.id()))
                .count();
        Map<String, Double> cosineByEventId =
                semantic.available() ? cosineScores(returnedHits, semantic, sample) : Map.of();
        List<RecalledItem> items = returnedHits.stream()
                .map(hit -> toRecalledItem(eventsById.get(hit.id()), cosineByEventId.get(hit.id())))
                .filter(Objects::nonNull)
                .toList();

        return new RecallResult(trimmedScope, hours, resolvedKinds, items.size(), items, mode);
    }

    private SemanticRecall semanticRecall(
            String trimmedScope,
            List<String> eventTypes,
            Instant since,
            Map<String, AgentEvent> eventsById,
            Sample sample) {
        if (trimmedScope == null || trimmedScope.isBlank()) {
            sample.fallbackReason = "blank_scope";

            return SemanticRecall.unavailable();
        }
        // Location/id requests preserve lexical recency ordering rather than embedding a location.
        if (pathOrIdScope(trimmedScope)) {
            sample.fallbackReason = "path_or_id_scope";

            return SemanticRecall.unavailable();
        }
        sample.semanticAttempted = true;
        long started = System.nanoTime();
        try {
            if (!embedder.available()) {
                sample.embeddingProbeOutcome = "unavailable";
                sample.fallbackReason = "embedding_unavailable";

                return SemanticRecall.unavailable();
            }
            sample.embeddingProbeOutcome = "success";
        } catch (RuntimeException ex) {
            sample.embeddingProbeOutcome = "error";
            sample.fallbackReason = "embedding_error";

            return SemanticRecall.unavailable();
        } finally {
            sample.embeddingProbeNanos = System.nanoTime() - started;
        }

        Map<String, RecallCandidate> candidatesByKey;
        Predicate<String> keyFilter;
        try {
            candidatesByKey = semanticCandidates(eventTypes, since);
            sample.semanticCandidates = candidatesByKey.size();
            keyFilter = semanticKeyFilter(candidatesByKey, trimmedScope);
        } catch (RuntimeException ex) {
            sample.fallbackReason = "candidates_error";

            return SemanticRecall.unavailable();
        }

        EmbeddingVector query;
        started = System.nanoTime();
        try {
            query = embedder.embedQuery(trimmedScope);
            sample.embeddingOutcome = "success";
        } catch (RuntimeException ex) {
            sample.embeddingOutcome = "error";
            sample.fallbackReason = "embedding_error";

            return SemanticRecall.unavailable();
        } finally {
            sample.embeddingNanos = System.nanoTime() - started;
        }

        started = System.nanoTime();
        try {
            List<ScoredKey> scoredKeys = vectorStore.knn(query, RECALL_LIMIT, keyFilter);
            List<MemoryHit> hits = new ArrayList<>();
            for (ScoredKey scored : scoredKeys) {
                if (!admitsSemanticScore(scored.score())) {
                    sample.gateRejected++;
                    continue;
                }
                sample.gateAdmitted++;
                MemoryHit hit = semanticHit(scored, candidatesByKey, eventsById);
                if (hit != null) hits.add(hit);
            }
            sample.vectorOutcome = "success";
            sample.semanticCompleted = true;
            sample.semanticContributed = !hits.isEmpty();
            sample.semanticHits = hits.size();

            return SemanticRecall.available(query, hits);
        } catch (RuntimeException ex) {
            sample.vectorOutcome = "error";
            sample.fallbackReason = "vector_error";

            return SemanticRecall.unavailable();
        } finally {
            sample.vectorNanos = System.nanoTime() - started;
        }
    }

    private boolean admitsSemanticScore(double score) {
        double floor = recallProperties.getRelevanceFloor();

        return floor <= 0.0 || score >= floor;
    }

    private Map<String, Double> cosineScores(List<MemoryHit> returnedHits, SemanticRecall semantic, Sample sample) {
        Map<String, Double> scores = new LinkedHashMap<>();
        for (MemoryHit hit : semantic.hits()) {
            if (hit != null && hit.id() != null) {
                scores.put(hit.id(), hit.score());
            }
        }

        List<String> unscoredKeys = returnedHits.stream()
                .map(MemoryHit::id)
                .filter(Objects::nonNull)
                .filter(id -> !scores.containsKey(id))
                .map(ContextService::eventVectorKey)
                .toList();
        if (unscoredKeys.isEmpty()) {

            return scores;
        }

        long started = System.nanoTime();
        try {
            Map<String, EmbeddingVector> vectors = vectorStore.fetchVectors(
                    unscoredKeys, semantic.query().model(), semantic.query().values().length);
            for (MemoryHit hit : returnedHits) {
                if (hit == null || hit.id() == null || scores.containsKey(hit.id())) {
                    continue;
                }
                EmbeddingVector vector = vectors.get(eventVectorKey(hit.id()));
                if (vector != null) {
                    scores.put(hit.id(), semantic.query().cosineSimilarity(vector));
                }
            }
            sample.vectorFetchOutcome = "success";
        } catch (RuntimeException ex) {
            // Optional score enrichment must not discard an already ranked, valid recall page.
            sample.vectorFetchOutcome = "error";
        } finally {
            sample.vectorFetchNanos = System.nanoTime() - started;
        }

        return scores;
    }

    private Map<String, RecallCandidate> semanticCandidates(List<String> eventTypes, Instant since) {
        Map<String, RecallCandidate> candidates = new LinkedHashMap<>();
        for (RecallCandidate candidate : repository.recallCandidates(eventTypes, since)) {
            if (candidate.event() != null && candidate.event().id() != null) {
                candidates.put(eventVectorKey(candidate.event().id()), candidate);
            }
        }

        return candidates;
    }

    private static Predicate<String> semanticKeyFilter(
            Map<String, RecallCandidate> candidatesByKey, String trimmedScope) {
        String scopeNeedle = trimmedScope == null ? "" : trimmedScope.toLowerCase(Locale.ROOT);
        boolean anchoredScope = anchoredScope(candidatesByKey, trimmedScope, scopeNeedle);

        return key -> {
            RecallCandidate candidate = candidatesByKey.get(key);
            if (candidate == null) {

                return false;
            }

            return !anchoredScope || literalScopeMatches(candidate, scopeNeedle);
        };
    }

    private static boolean anchoredScope(
            Map<String, RecallCandidate> candidatesByKey, String trimmedScope, String scopeNeedle) {
        if (trimmedScope == null || trimmedScope.isBlank()) {

            return false;
        }
        if (pathOrIdScope(trimmedScope)) {

            return true;
        }

        return candidatesByKey.values().stream().anyMatch(candidate -> literalScopeMatches(candidate, scopeNeedle));
    }

    private static boolean pathOrIdScope(String scope) {
        if (scope == null || scope.isBlank()) {

            return false;
        }
        String stripped = scope.strip();

        return stripped.contains("/") || stripped.matches("[0-9a-fA-F-]{32,}");
    }

    private static boolean literalScopeMatches(RecallCandidate candidate, String scopeNeedle) {
        AgentEvent event = candidate.event();
        if (event == null) {

            return false;
        }

        return containsIgnoreCase(event.id(), scopeNeedle)
                || containsIgnoreCase(candidate.cwd(), scopeNeedle)
                || containsIgnoreCase(
                        str(event.metadata() == null ? null : event.metadata().get("repo")), scopeNeedle)
                || containsIgnoreCase(event.text(), scopeNeedle);
    }

    private static boolean containsIgnoreCase(String value, String lowerNeedle) {

        return value != null && value.toLowerCase(Locale.ROOT).contains(lowerNeedle);
    }

    private static MemoryHit semanticHit(
            ScoredKey scored, Map<String, RecallCandidate> candidatesByKey, Map<String, AgentEvent> eventsById) {
        RecallCandidate candidate = candidatesByKey.get(scored.key());
        if (candidate == null || candidate.event() == null) {

            return null;
        }
        eventsById.putIfAbsent(candidate.event().id(), candidate.event());

        return toMemoryHit(candidate.event(), scored.score());
    }

    private static List<String> resolveKinds(List<String> kinds) {
        if (kinds == null || kinds.isEmpty()) {

            return DEFAULT_RECALL_KINDS;
        }
        List<String> resolved = new ArrayList<>();
        for (String kind : kinds) {
            if (kind == null) {
                continue;
            }
            String normalized = kind.strip().toLowerCase(Locale.ROOT);
            if (EVENT_TYPE_BY_KIND.containsKey(normalized) && !resolved.contains(normalized)) {
                resolved.add(normalized);
            }
        }

        return resolved.isEmpty() ? DEFAULT_RECALL_KINDS : resolved;
    }

    private static RecalledItem toRecalledItem(AgentEvent event, Double score) {
        if (event == null) {

            return null;
        }
        Map<String, Object> meta = event.metadata() == null ? Map.of() : event.metadata();
        String kind = str(meta.get("kind"));
        if (kind == null) {
            kind = event.eventType() == null
                    ? KIND_OBSERVATION
                    : event.eventType().toLowerCase(Locale.ROOT);
        }
        String headline =
                switch (kind) {
                    case KIND_DECISION -> firstNonBlank(str(meta.get("decision")), Titles.firstLine(event.text()));
                    case KIND_HANDOFF -> firstNonBlank(str(meta.get("contextSummary")), Titles.firstLine(event.text()));
                    case KIND_PROJECTION ->
                        firstNonBlank(
                                firstPathTitle(meta.get("paths")),
                                firstNonBlank(str(meta.get("basis")), Titles.firstLine(event.text())));
                    default -> firstNonBlank(Titles.firstLine(event.text()), event.eventType());
                };
        String rationale = KIND_PROJECTION.equals(kind) ? str(meta.get("basis")) : str(meta.get("rationale"));
        Double confidence = KIND_PROJECTION.equals(kind)
                ? firstPathConfidence(meta.get("paths"))
                : asDouble(meta.get("confidence"));

        return new RecalledItem(
                event.id(),
                event.sessionId(),
                kind,
                event.source(),
                event.clientSessionId(),
                str(meta.get("repo")),
                event.observedAt(),
                headline,
                rationale,
                asStringList(meta.get("alternatives")),
                confidence,
                asStringList(meta.get("openLoops")),
                str(meta.get("nextAction")),
                str(meta.get("toAgent")),
                score);
    }

    private static MemoryHit toMemoryHit(AgentEvent event, double score) {
        Map<String, Object> meta = event.metadata() == null ? Map.of() : event.metadata();
        String kind = str(meta.get("kind"));
        if (kind == null) {
            kind = event.eventType() == null
                    ? KIND_OBSERVATION
                    : event.eventType().toLowerCase(Locale.ROOT);
        }
        String headline =
                switch (kind) {
                    case KIND_DECISION -> firstNonBlank(str(meta.get("decision")), Titles.firstLine(event.text()));
                    case KIND_HANDOFF -> firstNonBlank(str(meta.get("contextSummary")), Titles.firstLine(event.text()));
                    case KIND_PROJECTION ->
                        firstNonBlank(
                                firstPathTitle(meta.get("paths")),
                                firstNonBlank(str(meta.get("basis")), Titles.firstLine(event.text())));
                    default -> firstNonBlank(Titles.firstLine(event.text()), event.eventType());
                };

        return new MemoryHit(
                event.id(),
                score,
                headline,
                event.source(),
                str(meta.get("repo")),
                event.sessionId(),
                event.clientSessionId(),
                event.observedAt() == null ? null : event.observedAt().toString(),
                event.text(),
                Titles.firstLine(event.text()));
    }

    private static String eventVectorKey(String eventId) {

        return VECTOR_EVENT_PREFIX + eventId;
    }

    private static String firstNonBlank(String first, String second) {

        return notBlank(first) ? first : second;
    }

    private static String firstPathTitle(Object value) {
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    String title = str(map.get("title"));
                    if (notBlank(title)) {

                        return title;
                    }
                }
            }
        }

        return null;
    }

    private static Double firstPathConfidence(Object value) {
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map && notBlank(str(map.get("title")))) {

                    return asDouble(map.get("confidence"));
                }
            }
        }

        return null;
    }

    private static boolean notBlank(String value) {

        return value != null && !value.isBlank();
    }

    private static String str(Object value) {

        return value == null ? null : String.valueOf(value);
    }

    private static Double asDouble(Object value) {
        if (value instanceof Number number) {

            return number.doubleValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {

                return Double.parseDouble(text.trim());
            } catch (NumberFormatException ignored) {

                return null;
            }
        }

        return null;
    }

    private static List<String> asStringList(Object value) {
        if (value instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object element : list) {
                if (element != null) {
                    out.add(String.valueOf(element));
                }
            }

            return out.isEmpty() ? null : out;
        }

        return null;
    }

    private record SemanticRecall(boolean available, EmbeddingVector query, List<MemoryHit> hits) {

        private static SemanticRecall available(EmbeddingVector query, List<MemoryHit> hits) {

            return new SemanticRecall(true, query, hits);
        }

        private static SemanticRecall unavailable() {

            return new SemanticRecall(false, null, List.of());
        }
    }
}
