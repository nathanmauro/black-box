package dev.nathan.sbaagentic.memory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.nathan.sbaagentic.memory.internal.application.port.TextEmbedder;
import dev.nathan.sbaagentic.memory.internal.domain.EmbeddableText;
import dev.nathan.sbaagentic.memory.internal.domain.EmbeddingVector;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@EnabledIfEnvironmentVariable(named = "SBA_EVAL_LIVE_MODEL", matches = "true")
class RecallCosineDistributionEvaluationTest {

    private static final int PRINT_TOP_K = 50;
    private static final TypeReference<List<EvalQuery>> QUERY_LIST = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final List<JunkQuery> JUNK_QUERIES = List.of(
            new JunkQuery("junk-cooking-01", "how long should sourdough focaccia proof before baking"),
            new JunkQuery("junk-cooking-02", "what spices belong in a slow cooker chicken tikka masala"),
            new JunkQuery("junk-sports-01", "best defensive formation for a youth soccer tournament"),
            new JunkQuery("junk-sports-02", "how to calculate a baseball pitcher's earned run average"),
            new JunkQuery("junk-gardening-01", "when should tomato seedlings be transplanted outdoors"),
            new JunkQuery("junk-gardening-02", "organic treatment for powdery mildew on zucchini leaves"),
            new JunkQuery("junk-travel-01", "quiet beaches near lisbon for a weekend trip"),
            new JunkQuery("junk-music-01", "how to tune a mandolin for bluegrass practice"),
            new JunkQuery("junk-fitness-01", "beginner kettlebell workout for stronger hamstrings"),
            new JunkQuery("junk-home-01", "how to remove mineral stains from a glass shower door"));

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    TextEmbedder embedder;

    @Test
    void printsCosineDistributionForEvalTargetsAndJunkQueries() throws IOException {
        Assumptions.assumeTrue(embedder.available(), "live memory embedding model is not reachable");

        List<EvalQuery> evalQueries = objectMapper.readValue(
                new ClassPathResource("eval/recall-queries.json").getInputStream(),
                QUERY_LIST);
        assertThat(evalQueries).hasSize(6);

        List<Candidate> corpus = embedCorpus(loadCorpus());
        Assumptions.assumeFalse(corpus.isEmpty(), "configured datasource has no captured recall event corpus");

        List<Double> targetCosines = new ArrayList<>();
        List<Double> junkMaxCosines = new ArrayList<>();

        for (int index = 0; index < evalQueries.size(); index++) {
            EvalQuery query = evalQueries.get(index);
            QueryMeasurement measurement = measure(
                    "eval-%02d".formatted(index + 1),
                    "eval",
                    query.query(),
                    Pattern.compile(query.expect(), Pattern.CASE_INSENSITIVE),
                    corpus);
            printRanks(measurement);
            printMax(measurement);
            if (measurement.targetMatch() != null) {
                targetCosines.add(measurement.targetMatch().score());
            }
        }

        for (JunkQuery query : JUNK_QUERIES) {
            QueryMeasurement measurement = measure(query.id(), "junk", query.query(), null, corpus);
            printRanks(measurement);
            printMax(measurement);
            if (!measurement.ranked().isEmpty()) {
                junkMaxCosines.add(measurement.ranked().getFirst().score());
            }
        }

        printSummary("targetCosine", targetCosines);
        printSummary("junkMaxCosine", junkMaxCosines);
    }

    private List<Candidate> loadCorpus() {
        try {
            return jdbcTemplate.query("""
                    SELECT id, event_type, text, metadata_json
                      FROM agent_events
                     WHERE lower(replace(replace(replace(event_type, '_', ''), '-', ''), ' ', ''))
                           IN ('decision', 'handoff', 'observation')
                       AND trim(coalesce(text, '')) <> ''
                     ORDER BY observed_at DESC, id DESC
                    """,
                    (rs, rowNum) -> {
                        String text = rs.getString("text");
                        String metadataJson = rs.getString("metadata_json");
                        String document = EmbeddableText.forEvent(
                                rs.getString("event_type"),
                                text,
                                fromJsonMap(metadataJson));
                        return new Candidate(
                                rs.getString("id"),
                                rs.getString("event_type"),
                                text == null ? "" : text,
                                document,
                                null);
                    });
        }
        catch (DataAccessException ex) {
            Assumptions.assumeTrue(false, () -> "configured datasource unavailable: " + ex.getMessage());
            return List.of();
        }
    }

    private List<Candidate> embedCorpus(List<Candidate> corpus) {
        try {
            return corpus.stream()
                    .filter(candidate -> !candidate.documentText().isBlank())
                    .map(candidate -> candidate.withVector(embedder.embedDocument(candidate.documentText())))
                    .toList();
        }
        catch (RuntimeException ex) {
            Assumptions.assumeTrue(false, () -> "live memory embedding model unavailable: " + ex.getMessage());
            return List.of();
        }
    }

    private QueryMeasurement measure(
            String queryId,
            String queryType,
            String query,
            Pattern targetPattern,
            List<Candidate> corpus) {
        try {
            EmbeddingVector queryVector = embedder.embedQuery(query);
            List<ScoredCandidate> ranked = corpus.stream()
                    .map(candidate -> new ScoredCandidate(candidate, queryVector.cosineSimilarity(candidate.vector())))
                    .sorted(Comparator.comparingDouble(ScoredCandidate::score).reversed()
                            .thenComparing(scored -> scored.candidate().id()))
                    .toList();
            ScoredCandidate targetMatch = null;
            if (targetPattern != null) {
                targetMatch = ranked.stream()
                        .filter(scored -> targetPattern.matcher(scored.candidate().eventText()).find())
                        .findFirst()
                        .orElse(null);
            }
            return new QueryMeasurement(queryId, queryType, query, targetPattern, ranked, targetMatch);
        }
        catch (RuntimeException ex) {
            Assumptions.assumeTrue(false, () -> "live memory embedding model unavailable: " + ex.getMessage());
            return new QueryMeasurement(queryId, queryType, query, targetPattern, List.of(), null);
        }
    }

    private void printRanks(QueryMeasurement measurement) {
        int rows = Math.min(PRINT_TOP_K, measurement.ranked().size());
        for (int index = 0; index < rows; index++) {
            ScoredCandidate scored = measurement.ranked().get(index);
            boolean target = measurement.targetPattern() != null
                    && measurement.targetPattern().matcher(scored.candidate().eventText()).find();
            System.out.printf(Locale.ROOT,
                    "COSINE_RANK queryId=%s queryType=%s rank=%d cosine=%s eventId=%s target=%s%n",
                    measurement.queryId(),
                    measurement.queryType(),
                    index + 1,
                    format(scored.score()),
                    scored.candidate().id(),
                    target);
        }
    }

    private static void printMax(QueryMeasurement measurement) {
        ScoredCandidate max = measurement.ranked().isEmpty() ? null : measurement.ranked().getFirst();
        int targetRank = targetRank(measurement);
        System.out.printf(Locale.ROOT,
                "COSINE_MAX queryId=%s queryType=%s maxCosine=%s eventId=%s targetRank=%d targetCosine=%s%n",
                measurement.queryId(),
                measurement.queryType(),
                max == null ? "null" : format(max.score()),
                max == null ? "none" : max.candidate().id(),
                targetRank,
                measurement.targetMatch() == null ? "null" : format(measurement.targetMatch().score()));
    }

    private static int targetRank(QueryMeasurement measurement) {
        if (measurement.targetMatch() == null) {
            return 0;
        }
        for (int index = 0; index < measurement.ranked().size(); index++) {
            if (measurement.ranked().get(index).candidate().id().equals(measurement.targetMatch().candidate().id())) {
                return index + 1;
            }
        }
        return 0;
    }

    private static void printSummary(String group, List<Double> values) {
        List<Double> sorted = values.stream().sorted().toList();
        System.out.printf(Locale.ROOT,
                "COSINE_SUMMARY group=%s count=%d min=%s p50=%s p90=%s max=%s values=%s%n",
                group,
                sorted.size(),
                percentile(sorted, 0.0),
                percentile(sorted, 0.5),
                percentile(sorted, 0.9),
                percentile(sorted, 1.0),
                sorted.stream().map(RecallCosineDistributionEvaluationTest::format).collect(Collectors.joining(",")));
    }

    private static String percentile(List<Double> sorted, double percentile) {
        if (sorted.isEmpty()) {
            return "null";
        }
        int index = (int) Math.ceil(percentile * sorted.size()) - 1;
        index = Math.max(0, Math.min(sorted.size() - 1, index));
        return format(sorted.get(index));
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.6f", value);
    }

    private Map<String, Object> fromJsonMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        }
        catch (JsonProcessingException ex) {
            return Map.of();
        }
    }

    private record EvalQuery(String query, String expect) {
    }

    private record JunkQuery(String id, String query) {
    }

    private record Candidate(
            String id,
            String eventType,
            String eventText,
            String documentText,
            EmbeddingVector vector) {

        private Candidate withVector(EmbeddingVector nextVector) {
            return new Candidate(id, eventType, eventText, documentText, nextVector);
        }
    }

    private record ScoredCandidate(Candidate candidate, double score) {
    }

    private record QueryMeasurement(
            String queryId,
            String queryType,
            String query,
            Pattern targetPattern,
            List<ScoredCandidate> ranked,
            ScoredCandidate targetMatch) {
    }
}
