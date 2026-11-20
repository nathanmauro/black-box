package dev.nathan.sbaagentic.memory.eval;

import static org.assertj.core.api.Assertions.assertThat;

import dev.nathan.sbaagentic.memory.CompactSearchOperations;
import dev.nathan.sbaagentic.memory.MemoryRecallOperations;
import dev.nathan.sbaagentic.memory.eval.RecallBenchmarkHarness.Fixture;
import dev.nathan.sbaagentic.memory.eval.RecallBenchmarkHarness.QueryOutcome;
import dev.nathan.sbaagentic.memory.eval.RecallBenchmarkHarness.Report;
import dev.nathan.sbaagentic.memory.eval.RecallBenchmarkHarness.SuiteSummary;
import dev.nathan.sbaagentic.project.BraidDiscoveryOperations;
import dev.nathan.sbaagentic.project.ProjectMeldOperations;
import dev.nathan.sbaagentic.recording.RecordingCaptureOperations;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Offline recall quality benchmark: a seeded multi-project corpus, the real capture and recall
 * stack on a throwaway SQLite file, and a deterministic hashing embedder in place of Ollama. No
 * Elasticsearch, model server, or network call is involved.
 *
 * <p>Run it alone with {@code scripts/recall-benchmark.sh}. System properties
 * {@code sba.bench.iterations} (measured runs per query, default 3) and
 * {@code sba.bench.report-dir} (default {@code target/recall-benchmark}) tune the run.
 *
 * <p>The floors below are regression gates set just under the measured baseline, not targets.
 * Raise them when a ranking change genuinely improves recall; never lower one to hide a drop
 * without recording why.
 */
@SpringBootTest(
        properties = {
            "spring.datasource.url=jdbc:sqlite:${java.io.tmpdir}/bb-recall-benchmark-${random.uuid}.db",
            "sba.local-ai.enabled=false",
            "sba.summary.backend=local",
            "sba.elasticsearch.enabled=false",
            "sba.ask.embedding-enabled=false",
            "sba.memory.embedding.enabled=false",
            "sba.judge.enabled=false"
        })
@DisplayName("Recall quality benchmark: precision, MRR, NDCG, and latency over a synthetic corpus")
class RecallQualityBenchmarkTest {

    @Autowired
    RecordingCaptureOperations captures;

    @Autowired
    MemoryRecallOperations recall;

    @Autowired
    CompactSearchOperations search;

    @Autowired
    BraidDiscoveryOperations braids;

    @Autowired
    ProjectMeldOperations melds;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    HashingTextEmbedder embedder;

    @Test
    void benchmarkMeetsQualityFloorsAndIsDeterministic() {
        RecallBenchmarkHarness harness =
                new RecallBenchmarkHarness(captures, recall, search, braids, melds, jdbc, embedder);
        Fixture fixture = harness.seed();
        int iterations = Integer.getInteger("sba.bench.iterations", 3);

        Report report = harness.run(fixture, iterations);
        Path markdown = RecallBenchmarkReportWriter.write(
                report, Path.of(System.getProperty("sba.bench.report-dir", "target/recall-benchmark")));
        System.out.println(RecallBenchmarkReportWriter.markdown(report));
        System.out.println("Recall benchmark report: " + markdown.toAbsolutePath());

        assertThat(fixture.events()).isEqualTo(SyntheticRecallCorpus.items().size());
        assertThat(fixture.braids()).isEqualTo(SyntheticRecallCorpus.braids().size());
        assertDeterministic(harness, fixture, report);
        assertInvariants(report);
        assertQualityFloors(report.suites());
    }

    private static void assertDeterministic(RecallBenchmarkHarness harness, Fixture fixture, Report report) {
        Report rerun = harness.run(fixture, 1);
        Map<String, QueryOutcome> rerunById = rerun.queries().stream()
                .collect(Collectors.toMap(RecallQualityBenchmarkTest::outcomeKey, Function.identity()));
        for (QueryOutcome outcome : report.queries()) {
            assertThat(rerunById.get(outcomeKey(outcome)).rankedKeys())
                    .as("ranking for %s in %s is reproducible", outcome.id(), outcome.suite())
                    .isEqualTo(outcome.rankedKeys());
        }
    }

    private static void assertInvariants(Report report) {
        for (SuiteSummary suite : report.suites().values()) {
            assertThat(suite.unstableQueries())
                    .as("%s unstable queries", suite.suite())
                    .isZero();
            assertThat(suite.leakedItems())
                    .as("%s out-of-project items", suite.suite())
                    .isZero();
            assertThat(suite.latencyP50Ms()).as("%s p50", suite.suite()).isPositive();
        }
        Map<String, SuiteSummary> suites = report.suites();
        assertThat(suites.get("recall.exact.hybrid").modes()).containsOnlyKeys("hybrid");
        assertThat(suites.get("recall.paraphrase.lexical").modes()).containsOnlyKeys("lexical");
        // The reason hybrid recall exists: paraphrases a whole-phrase LIKE cannot match.
        assertThat(suites.get("recall.paraphrase.hybrid").ndcgAt10())
                .isGreaterThan(suites.get("recall.paraphrase.lexical").ndcgAt10());
    }

    /**
     * Baseline (2026-11-19, four runs): exact NDCG 0.883-0.894, paraphrase 0.804, project 0.830,
     * compact search 0.863, braids 1.000; MRR 1.000 everywhere hybrid or lexical can answer. Exact
     * recall varies run to run because rank-fusion ties fall back to random event UUIDs.
     */
    private static void assertQualityFloors(Map<String, SuiteSummary> suites) {
        floor(suites, "recall.exact.hybrid", 0.95, 0.85);
        floor(suites, "recall.paraphrase.hybrid", 0.95, 0.75);
        floor(suites, "recall.project.hybrid", 0.95, 0.78);
        floor(suites, "search.compact", 0.95, 0.80);
        floor(suites, "braids.find", 0.95, 0.95);
        assertThat(suites.get("recall.exact.hybrid").precisionAt5())
                .as("recall.exact.hybrid P@5")
                .isGreaterThanOrEqualTo(0.9);
    }

    private static void floor(Map<String, SuiteSummary> suites, String suite, double mrr, double ndcg) {
        SuiteSummary summary = suites.get(suite);
        assertThat(summary.mrr()).as("%s MRR", suite).isGreaterThanOrEqualTo(mrr);
        assertThat(summary.ndcgAt10()).as("%s NDCG@10", suite).isGreaterThanOrEqualTo(ndcg);
    }

    private static String outcomeKey(QueryOutcome outcome) {

        return outcome.suite() + "|" + outcome.id();
    }

    @TestConfiguration
    static class BenchmarkEmbedderConfig {

        @Bean
        @Primary
        HashingTextEmbedder hashingTextEmbedder() {

            return new HashingTextEmbedder(SyntheticRecallCorpus.LEXICON);
        }
    }
}
