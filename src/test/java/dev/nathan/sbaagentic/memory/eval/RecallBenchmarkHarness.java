package dev.nathan.sbaagentic.memory.eval;

import dev.nathan.sbaagentic.memory.CompactSearchOperations;
import dev.nathan.sbaagentic.memory.CompactSearchResult;
import dev.nathan.sbaagentic.memory.MemoryRecallOperations;
import dev.nathan.sbaagentic.memory.RecallResult;
import dev.nathan.sbaagentic.memory.RecalledItem;
import dev.nathan.sbaagentic.memory.eval.SyntheticRecallCorpus.Braid;
import dev.nathan.sbaagentic.memory.eval.SyntheticRecallCorpus.Item;
import dev.nathan.sbaagentic.memory.eval.SyntheticRecallCorpus.Kind;
import dev.nathan.sbaagentic.memory.eval.SyntheticRecallCorpus.Query;
import dev.nathan.sbaagentic.project.BraidDiscoveryOperations;
import dev.nathan.sbaagentic.project.BraidDiscoveryResult;
import dev.nathan.sbaagentic.project.ProjectKey;
import dev.nathan.sbaagentic.project.ProjectMeldOperations;
import dev.nathan.sbaagentic.project.ProjectMeldSaveRequest;
import dev.nathan.sbaagentic.project.ProjectSavedMeld;
import dev.nathan.sbaagentic.recording.CaptureDecisionRequest;
import dev.nathan.sbaagentic.recording.CaptureHandoffRequest;
import dev.nathan.sbaagentic.recording.IngestResponse;
import dev.nathan.sbaagentic.recording.RecordingCaptureOperations;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.function.Function;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Seeds {@link SyntheticRecallCorpus} through the real capture use cases, runs every benchmark
 * query against the real recall, compact-search, and braid-discovery surfaces, and scores the
 * rankings. Only the embedder is substituted; SQLite, sqlite-vec, fusion, and scoping are real.
 */
final class RecallBenchmarkHarness {

    static final String SOURCE = "bench";
    static final int K = 10;
    static final List<String> INTENT_KINDS = List.of("decision", "handoff", "observation");

    private final RecordingCaptureOperations captures;
    private final MemoryRecallOperations recall;
    private final CompactSearchOperations search;
    private final BraidDiscoveryOperations braids;
    private final ProjectMeldOperations melds;
    private final JdbcTemplate jdbc;
    private final HashingTextEmbedder embedder;

    RecallBenchmarkHarness(
            RecordingCaptureOperations captures,
            MemoryRecallOperations recall,
            CompactSearchOperations search,
            BraidDiscoveryOperations braids,
            ProjectMeldOperations melds,
            JdbcTemplate jdbc,
            HashingTextEmbedder embedder) {
        this.captures = captures;
        this.recall = recall;
        this.search = search;
        this.braids = braids;
        this.melds = melds;
        this.jdbc = jdbc;
        this.embedder = embedder;
    }

    /** Database id to fixture key, for everything the harness seeded. */
    record Fixture(Map<String, String> keysById, Map<String, String> projectByKey, int events, int braids) {

        String keyFor(String id) {

            return keysById.getOrDefault(id, "unseeded:" + id);
        }
    }

    record QueryOutcome(
            String id,
            String suite,
            String query,
            String project,
            String mode,
            List<String> rankedKeys,
            int relevant,
            double precisionAt1,
            double precisionAt5,
            double recallAt10,
            double reciprocalRank,
            double ndcgAt10,
            int leakedItems,
            boolean stableAcrossIterations,
            List<Long> latencyNanos) {}

    record SuiteSummary(
            String suite,
            int queries,
            double precisionAt1,
            double precisionAt5,
            double recallAt10,
            double mrr,
            double ndcgAt10,
            double zeroResultRate,
            int leakedItems,
            int unstableQueries,
            Map<String, Integer> modes,
            double latencyP50Ms,
            double latencyP90Ms,
            double latencyP99Ms,
            double latencyMaxMs) {}

    record Report(
            String corpusVersion,
            int projects,
            int intentEvents,
            int braids,
            int iterations,
            Map<String, SuiteSummary> suites,
            List<QueryOutcome> queries) {}

    private record Ranked(String mode, List<String> ids) {}

    private record Suite(String name, String corpusSuite, boolean semantic, Function<Query, Ranked> runner) {}

    Fixture seed() {
        Map<String, String> keysById = new LinkedHashMap<>();
        Map<String, String> projectByKey = new LinkedHashMap<>();
        Map<String, String> sessionByClientId = new LinkedHashMap<>();
        List<String> eventIdsInCaptureOrder = new ArrayList<>();

        for (Item item : SyntheticRecallCorpus.items()) {
            IngestResponse response = capture(item);
            keysById.put(response.eventId(), item.key());
            projectByKey.put(item.key(), item.project());
            sessionByClientId.putIfAbsent(item.clientSessionId(), response.sessionId());
            eventIdsInCaptureOrder.add(response.eventId());
        }

        List<Braid> braidFixtures = new ArrayList<>(SyntheticRecallCorpus.braids());
        Collections.shuffle(braidFixtures, new Random(SyntheticRecallCorpus.SEED));
        List<String> braidIdsInSaveOrder = new ArrayList<>();
        for (Braid braid : braidFixtures) {
            List<String> sessionIds = braid.topics().stream()
                    .map(topic -> sessionByClientId.get("bench-" + braid.project() + "-" + topic.key()))
                    .toList();
            ProjectSavedMeld saved = melds.save(new ProjectMeldSaveRequest(
                    ProjectKey.of(SyntheticRecallCorpus.repoFor(braid.project()))
                            .encoded(),
                    braid.title(),
                    braid.body(),
                    null,
                    null,
                    null,
                    "export_bundle",
                    true,
                    sessionIds,
                    Map.of("kind", "braid")));
            keysById.put(saved.id(), braid.key());
            projectByKey.put(braid.key(), braid.project());
            braidIdsInSaveOrder.add(saved.id());
        }

        pinTimestamps("agent_events", "observed_at", eventIdsInCaptureOrder);
        pinTimestamps("session_melds", "created_at", braidIdsInSaveOrder);

        return new Fixture(
                Map.copyOf(keysById),
                Map.copyOf(projectByKey),
                eventIdsInCaptureOrder.size(),
                braidIdsInSaveOrder.size());
    }

    Report run(Fixture fixture, int iterations) {
        if (iterations < 1) {
            throw new IllegalArgumentException("iterations must be at least 1");
        }
        List<Suite> suites = List.of(
                new Suite("recall.exact.hybrid", "recall.exact", true, this::recallUnscoped),
                new Suite("recall.paraphrase.hybrid", "recall.paraphrase", true, this::recallUnscoped),
                new Suite("recall.paraphrase.lexical", "recall.paraphrase", false, this::recallUnscoped),
                new Suite("recall.project.hybrid", "recall.project", true, this::recallProject),
                new Suite("search.compact", "search.compact", false, this::compactSearch),
                new Suite("braids.find", "braids.find", false, this::findBraids));

        List<Query> queries = SyntheticRecallCorpus.queries();
        List<QueryOutcome> outcomes = new ArrayList<>();
        Map<String, SuiteSummary> summaries = new LinkedHashMap<>();
        try {
            for (Suite suite : suites) {
                embedder.available(suite.semantic());
                List<QueryOutcome> suiteOutcomes = new ArrayList<>();
                for (Query query : queries) {
                    if (query.suite().equals(suite.corpusSuite())) {
                        suiteOutcomes.add(measure(suite, query, fixture, iterations));
                    }
                }
                outcomes.addAll(suiteOutcomes);
                summaries.put(suite.name(), summarize(suite.name(), suiteOutcomes));
            }
        } finally {
            embedder.available(true);
        }

        return new Report(
                HashingTextEmbedder.MODEL + "/seed-" + SyntheticRecallCorpus.SEED,
                SyntheticRecallCorpus.PROJECTS.size(),
                fixture.events(),
                fixture.braids(),
                iterations,
                summaries,
                outcomes);
    }

    private QueryOutcome measure(Suite suite, Query query, Fixture fixture, int iterations) {
        // One unmeasured warm-up keeps first-call class loading and statement prep out of p99.
        Ranked first = suite.runner().apply(query);
        List<Long> latencies = new ArrayList<>();
        boolean stable = true;
        for (int i = 0; i < iterations; i++) {
            long started = System.nanoTime();
            Ranked ranked = suite.runner().apply(query);
            latencies.add(System.nanoTime() - started);
            stable &= ranked.ids().equals(first.ids());
        }

        List<String> keys = first.ids().stream().map(fixture::keyFor).toList();
        Map<String, Integer> grades = query.grades();
        int leaked = query.project() == null
                ? 0
                : (int) keys.stream()
                        .filter(key -> !query.project().equals(projectOf(fixture, key)))
                        .count();

        return new QueryOutcome(
                query.id(),
                suite.name(),
                query.text(),
                query.project(),
                first.mode(),
                keys,
                (int) grades.values().stream().filter(grade -> grade > 0).count(),
                RankingMetrics.precisionAtK(keys, grades, 1),
                RankingMetrics.precisionAtK(keys, grades, 5),
                RankingMetrics.recallAtK(keys, grades, K),
                RankingMetrics.reciprocalRank(keys, grades),
                RankingMetrics.ndcgAtK(keys, grades, K),
                leaked,
                stable,
                List.copyOf(latencies));
    }

    private static String projectOf(Fixture fixture, String key) {

        return fixture.projectByKey().getOrDefault(key, "unknown");
    }

    private static SuiteSummary summarize(String suite, List<QueryOutcome> outcomes) {
        List<Long> latencies = outcomes.stream()
                .flatMap(outcome -> outcome.latencyNanos().stream())
                .toList();
        Map<String, Integer> modes = new LinkedHashMap<>();
        for (QueryOutcome outcome : outcomes) {
            modes.merge(outcome.mode(), 1, Integer::sum);
        }

        return new SuiteSummary(
                suite,
                outcomes.size(),
                mean(outcomes, QueryOutcome::precisionAt1),
                mean(outcomes, QueryOutcome::precisionAt5),
                mean(outcomes, QueryOutcome::recallAt10),
                mean(outcomes, QueryOutcome::reciprocalRank),
                mean(outcomes, QueryOutcome::ndcgAt10),
                (double) outcomes.stream()
                                .filter(outcome -> outcome.rankedKeys().isEmpty())
                                .count()
                        / outcomes.size(),
                outcomes.stream().mapToInt(QueryOutcome::leakedItems).sum(),
                (int) outcomes.stream()
                        .filter(outcome -> !outcome.stableAcrossIterations())
                        .count(),
                modes,
                millis(RankingMetrics.percentile(latencies, 50)),
                millis(RankingMetrics.percentile(latencies, 90)),
                millis(RankingMetrics.percentile(latencies, 99)),
                millis(RankingMetrics.percentile(latencies, 100)));
    }

    private static double mean(List<QueryOutcome> outcomes, java.util.function.ToDoubleFunction<QueryOutcome> metric) {

        return outcomes.stream().mapToDouble(metric).average().orElse(0.0);
    }

    private static double millis(long nanos) {

        return Math.round(nanos / 10_000.0) / 100.0;
    }

    private Ranked recallUnscoped(Query query) {
        RecallResult result = recall.recall(null, null, query.text(), 168, INTENT_KINDS, K, false);

        return new Ranked(
                result.mode(),
                result.items().stream().map(RecalledItem::eventId).toList());
    }

    private Ranked recallProject(Query query) {
        RecallResult result = recall.recall(
                null, SyntheticRecallCorpus.repoFor(query.project()), query.text(), 168, INTENT_KINDS, K, false);

        return new Ranked(
                result.mode(),
                result.items().stream().map(RecalledItem::eventId).toList());
    }

    private Ranked compactSearch(Query query) {
        CompactSearchResult result = search.search(query.text(), K, 64_000, null, false);
        if (!"ok".equals(result.status())) {
            throw new IllegalStateException(
                    "compact search failed for " + query.id() + ": " + result.status() + " " + result.diagnostics());
        }

        return new Ranked(
                "local",
                result.items().stream().map(CompactSearchResult.Hit::eventId).toList());
    }

    private Ranked findBraids(Query query) {
        BraidDiscoveryResult result = braids.find(null, query.text(), null, K, null, 64_000);
        if (!"ok".equals(result.status())) {
            throw new IllegalStateException("braid discovery failed for " + query.id() + ": " + result.diagnostics());
        }

        return new Ranked(
                "recency",
                result.items().stream()
                        .map(BraidDiscoveryResult.Hit::artifactId)
                        .toList());
    }

    private IngestResponse capture(Item item) {
        if (item.kind() == Kind.DECISION) {

            return captures.captureDecision(new CaptureDecisionRequest(
                    SOURCE,
                    item.clientSessionId(),
                    item.repo(),
                    item.text(),
                    item.topic().rationale(),
                    List.of("leave it as is"),
                    0.8,
                    List.of()));
        }
        if (item.kind() == Kind.HANDOFF) {

            return captures.captureHandoff(new CaptureHandoffRequest(
                    SOURCE,
                    item.clientSessionId(),
                    item.repo(),
                    "next-agent",
                    item.text(),
                    List.of(),
                    item.topic().nextAction()));
        }

        return captures.captureObservation(SOURCE, item.clientSessionId(), item.repo(), item.text());
    }

    /**
     * Rewrites the given rows' timestamps to one-second steps in seeding order. Captures within the
     * same millisecond would otherwise tie and fall back to random UUID order, which made rankings
     * vary between runs.
     */
    private void pinTimestamps(String table, String column, List<String> idsInOrder) {
        Instant base = Instant.now().truncatedTo(ChronoUnit.SECONDS).minus(2, ChronoUnit.HOURS);
        for (int i = 0; i < idsInOrder.size(); i++) {
            int updated = jdbc.update(
                    "UPDATE " + table + " SET " + column + " = ? WHERE id = ?",
                    base.plusSeconds(i).toString(),
                    idsInOrder.get(i));
            if (updated != 1) {
                throw new IllegalStateException(String.format(
                        Locale.ROOT, "expected one %s row for %s, updated %d", table, idsInOrder.get(i), updated));
            }
        }
    }
}
