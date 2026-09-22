package dev.nathan.sbaagentic.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nathan.sbaagentic.memory.internal.application.port.TextEmbedder;
import dev.nathan.sbaagentic.memory.internal.domain.EmbeddableText;
import dev.nathan.sbaagentic.memory.internal.domain.EmbeddingVector;
import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@EnabledIfEnvironmentVariable(named = "SBA_EVAL_LIVE_MODEL", matches = "true")
class RecallRelevanceEvaluationTest {

    private static final TypeReference<List<EvalQuery>> QUERY_LIST = new TypeReference<>() {};
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    TextEmbedder embedder;

    @Test
    void liveModelRetrievesRelevantCapturedEvents() throws IOException {
        Assumptions.assumeTrue(embedder.available(), "live memory embedding model is not reachable");

        List<EvalQuery> queries =
                objectMapper.readValue(new ClassPathResource("eval/recall-queries.json").getInputStream(), QUERY_LIST);
        assertThat(queries).hasSize(6);

        List<Candidate> corpus = embedCorpus(loadCorpus());
        Assumptions.assumeFalse(corpus.isEmpty(), "configured datasource has no captured recall event corpus");

        int recallAt1 = 0;
        int recallAt5 = 0;
        for (EvalQuery query : queries) {
            QueryResult result = rank(query, corpus);
            if (result.rank() == 1) {
                recallAt1++;
            }
            if (result.rank() > 0 && result.rank() <= 5) {
                recallAt5++;
            }
            printResult(query, result);
        }
        System.out.printf("recall@1=%d/%d recall@5=%d/%d%n", recallAt1, queries.size(), recallAt5, queries.size());

        assertThat(recallAt5).as("recall@5").isGreaterThanOrEqualTo(4);
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
                    """, (rs, rowNum) -> {
                String text = rs.getString("text");
                String metadataJson = rs.getString("metadata_json");
                String document = EmbeddableText.forEvent(rs.getString("event_type"), text, fromJsonMap(metadataJson));

                return new Candidate(
                        rs.getString("id"), rs.getString("event_type"), text == null ? "" : text, document, null);
            });
        } catch (DataAccessException ex) {
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
        } catch (RuntimeException ex) {
            Assumptions.assumeTrue(false, () -> "live memory embedding model unavailable: " + ex.getMessage());

            return List.of();
        }
    }

    private QueryResult rank(EvalQuery query, List<Candidate> corpus) {
        try {
            EmbeddingVector queryVector = embedder.embedQuery(query.query());
            Pattern expected = Pattern.compile(query.expect(), Pattern.CASE_INSENSITIVE);
            List<ScoredCandidate> ranked = corpus.stream()
                    .map(candidate -> new ScoredCandidate(candidate, queryVector.cosineSimilarity(candidate.vector())))
                    .sorted(Comparator.comparingDouble(ScoredCandidate::score)
                            .reversed()
                            .thenComparing(scored -> scored.candidate().id()))
                    .toList();
            for (int index = 0; index < ranked.size(); index++) {
                ScoredCandidate scored = ranked.get(index);
                if (expected.matcher(scored.candidate().eventText()).find()) {

                    return new QueryResult(index + 1, scored);
                }
            }

            return new QueryResult(0, ranked.isEmpty() ? null : ranked.getFirst());
        } catch (RuntimeException ex) {
            Assumptions.assumeTrue(false, () -> "live memory embedding model unavailable: " + ex.getMessage());

            return new QueryResult(0, null);
        }
    }

    private void printResult(EvalQuery query, QueryResult result) {
        if (result.rank() > 0) {
            ScoredCandidate scored = result.match();
            System.out.printf(
                    "query=\"%s\" rank=%d id=%s type=%s score=%.4f%n",
                    query.query(),
                    result.rank(),
                    scored.candidate().id(),
                    scored.candidate().eventType(),
                    scored.score());

            return;
        }
        ScoredCandidate top = result.match();
        String topId = top == null ? "none" : top.candidate().id();
        System.out.printf("query=\"%s\" MISS top=%s%n", query.query(), topId);
    }

    private Map<String, Object> fromJsonMap(String json) {
        if (json == null || json.isBlank()) {

            return Map.of();
        }
        try {

            return objectMapper.readValue(json, MAP_TYPE);
        } catch (JsonProcessingException ex) {

            return Map.of();
        }
    }

    private record EvalQuery(String query, String expect) {}

    private record Candidate(
            String id, String eventType, String eventText, String documentText, EmbeddingVector vector) {

        private Candidate withVector(EmbeddingVector nextVector) {

            return new Candidate(id, eventType, eventText, documentText, nextVector);
        }
    }

    private record ScoredCandidate(Candidate candidate, double score) {}

    private record QueryResult(int rank, ScoredCandidate match) {}
}
