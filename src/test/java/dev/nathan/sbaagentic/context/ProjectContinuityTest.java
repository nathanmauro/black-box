package dev.nathan.sbaagentic.context;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nathan.sbaagentic.memory.*;
import dev.nathan.sbaagentic.memory.internal.application.port.TextEmbedder;
import dev.nathan.sbaagentic.memory.internal.domain.EmbeddingVector;
import dev.nathan.sbaagentic.project.ProjectAliasRequest;
import dev.nathan.sbaagentic.project.internal.application.ProjectAliasService;
import dev.nathan.sbaagentic.recording.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
        properties = {
            "spring.datasource.url=jdbc:sqlite:${java.io.tmpdir}/bb-continuity-${random.uuid}.db",
            "sba.local-ai.enabled=false",
            "sba.summary.backend=local",
            "sba.elasticsearch.enabled=false",
            "sba.ask.embedding-enabled=false",
            "sba.memory.embedding.enabled=false"
        })
@AutoConfigureMockMvc
class ProjectContinuityTest {
    @Autowired
    RecordingCaptureOperations captures;

    @Autowired
    EventRecorder recorder;

    @Autowired
    MemoryRecallOperations recall;

    @Autowired
    ProjectAliasService aliases;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MockMvc http;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    FixtureEmbedder embedder;

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM decision_replacements");
        jdbc.update("DELETE FROM memory_embeddings");
        jdbc.update("DELETE FROM agent_events");
        jdbc.update("DELETE FROM agent_sessions");
        jdbc.update("DELETE FROM project_aliases");
        embedder.available = true;
        embedder.lastQuery = null;
    }

    @Test
    void exactProjectAndAliasesConstrainLexicalAndSemanticBeforeTopK() {
        String chosen = decision("/fixture/chosen", "Storage design", null).eventId();
        String alias = decision("/fixture/worktree/", "Storage synonym", null).eventId();
        aliases.put(new ProjectAliasRequest("/fixture/worktree", "/fixture/chosen"));
        for (int i = 0; i < 65; i++) decision("/fixture/chosen-neighbor", "Storage design " + i, null);
        var result = query("/fixture/chosen/", "Storage", false);
        assertThat(result.mode()).isEqualTo("hybrid");
        assertThat(ids(result)).containsExactlyInAnyOrder(chosen, alias);
        assertThat(embedder.lastQuery).isEqualTo("Storage");
        assertThat(ids(query("/fixture/worktree", "question/with/slashes", false)))
                .containsExactlyInAnyOrder(chosen, alias);
        assertThat(embedder.lastQuery).isEqualTo("question/with/slashes");
        assertThat(ids(query("/fixture/missing", "Storage", false))).isEmpty();
        embedder.available = false;
        var degraded = query("/fixture/chosen", "Storage", false);
        assertThat(degraded.mode()).isEqualTo("lexical");
        assertThat(ids(degraded)).containsExactlyInAnyOrder(chosen, alias);
        assertThat(ids(query("/fixture/missing", "Storage", false))).isEmpty();
    }

    @Test
    void blankProjectQueryIsRecentAndNeverEmbedded() {
        String wanted = decision("/fixture/recent", "A recorded choice", null).eventId();
        decision("/fixture/other", "A newer choice", null);
        var result = query("/fixture/recent", "", false);
        assertThat(ids(result)).containsExactly(wanted);
        assertThat(result.mode()).isEqualTo("lexical");
        assertThat(embedder.lastQuery).isNull();
    }

    @Test
    void metadataRepoPreventsOldEventsMovingWhenSessionCwdChanges() {
        var old = captures.captureDecision(request("shared-session", "/fixture/first", "First", null));
        captures.captureDecision(request("shared-session", "/fixture/second", "Second", null));
        assertThat(ids(query("/fixture/first", "", false))).containsExactly(old.eventId());
        assertThat(query("/fixture/second", "", false).items())
                .extracting(RecalledItem::headline)
                .containsExactly("Second");
    }

    @Test
    void explicitQueryUsesLiteralLikeCharactersAndDoesNotMatchDirectory() {
        embedder.available = false;
        String literal = decision("/fixture/percent", "Keep 100%_literal", null).eventId();
        decision("/fixture/percent", "Keep 100xxliteral", null);
        assertThat(ids(query("/fixture/percent", "%_", false))).containsExactly(literal);
        assertThat(ids(query("/fixture/percent", "fixture/percent", false))).isEmpty();
    }

    @Test
    void replacementRetainsOriginalBytesAndSupportsHistoricalChainAcrossAliases() {
        String first = decision("/fixture/original", "Use A", null).eventId();
        var originalBytes = jdbc.queryForMap("SELECT * FROM agent_events WHERE id = ?", first);
        aliases.put(new ProjectAliasRequest("/fixture/alias", "/fixture/original"));
        String second = decision("/fixture/alias", "Use B", first).eventId();
        String third = decision("/fixture/original", "Use C", second).eventId();
        assertThat(jdbc.queryForMap("SELECT * FROM agent_events WHERE id = ?", first))
                .isEqualTo(originalBytes);
        assertThat(ids(query("/fixture/original", "", false))).containsExactly(third);
        var history = query("/fixture/original", "", true);
        assertThat(ids(history)).containsExactlyInAnyOrder(first, second, third);
        assertThat(item(history, first).supersededByEventId()).isEqualTo(second);
        assertThat(item(history, second).supersedesEventId()).isEqualTo(first);
        assertThat(item(history, second).supersededByEventId()).isEqualTo(third);
        assertThat(item(history, third).supersedesEventId()).isEqualTo(second);
    }

    @Test
    void replacementOutsideQueryOrTimeWindowStillSuppressesOldDecision() {
        String old = decision("/fixture/window", "Old beacon", null).eventId();
        String replacement = decision("/fixture/window", "Replacement without former wording", old)
                .eventId();
        jdbc.update(
                "UPDATE agent_events SET observed_at = ? WHERE id = ?",
                Instant.now().minus(10, ChronoUnit.DAYS).toString(),
                replacement);
        embedder.available = false;
        assertThat(ids(query("/fixture/window", "beacon", false))).isEmpty();
        var history = query("/fixture/window", "beacon", true);
        assertThat(ids(history)).containsExactly(old);
        assertThat(item(history, old).supersededByEventId()).isEqualTo(replacement);
        assertThat(ids(recall.recall(old, 168, List.of("decision")))).isEmpty();
        assertThat(ids(recall.recall(old, null, null, 168, List.of("decision"), 50, true)))
                .containsExactly(old);
        embedder.available = true;
        assertThat(ids(query("/fixture/window", "beacon", false))).isEmpty();
    }

    @Test
    void invalidTargetsFailWithoutPartialEventSessionOrRelationWrites() {
        String target = decision("/fixture/valid", "Target", null).eventId();
        String observation = captures.captureObservation("manual", "observation", "/fixture/valid", "Fact")
                .eventId();
        long before = count("agent_events");
        for (String invalid : List.of("missing", observation)) {
            assertThatThrownBy(() -> decision("/fixture/valid", "Invalid", invalid))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> decision("/fixture/wrong", "Invalid", target))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> captures.captureDecision(new CaptureDecisionRequest(
                        "manual", "blank-reason", "/fixture/valid", "Invalid", " ", null, null, null, target)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(count("agent_events")).isEqualTo(before);
        assertThat(count("agent_sessions")).isEqualTo(2);
        assertThat(count("decision_replacements")).isZero();
        decision("/fixture/valid", "Valid", target);
        assertThatThrownBy(() -> decision("/fixture/valid", "Again", target))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(count("agent_events")).isEqualTo(before + 1);
        assertThat(count("decision_replacements")).isEqualTo(1);
    }

    @Test
    void unscopedTargetsCannotBeReplacedByAssumingAProject() {
        String target = decision(null, "Unscoped choice", null).eventId();
        for (String repo : List.of("/fixture/unrelated", "__no_project__", "__no_project__/", " ")) {
            assertThatThrownBy(() -> decision(repo, "Replacement", target))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(count("agent_events")).isEqualTo(1);
        assertThat(count("decision_replacements")).isZero();
    }

    @Test
    void failedEventInsertRollsBackReservedRelationAndSession() {
        String target = decision("/fixture/rollback", "Target", null).eventId();
        jdbc.execute(
                "CREATE TRIGGER reject_replacement BEFORE INSERT ON agent_events WHEN new.client_session_id = 'rollback-replacement' BEGIN SELECT RAISE(FAIL, 'fixture rejected'); END");
        try {
            assertThatThrownBy(() -> captures.captureDecision(
                            request("rollback-replacement", "/fixture/rollback", "Will fail", target)))
                    .isInstanceOf(RuntimeException.class);
            assertThat(count("agent_events")).isEqualTo(1);
            assertThat(count("agent_sessions")).isEqualTo(1);
            assertThat(count("decision_replacements")).isZero();
            assertThat(ids(query("/fixture/rollback", "", false))).containsExactly(target);
        } finally {
            jdbc.execute("DROP TRIGGER reject_replacement");
        }
        decision("/fixture/rollback", "Retry works", target);
        assertThat(count("decision_replacements")).isEqualTo(1);
    }

    @Test
    void concurrentReplacementsChooseOneWinnerAndPublishNoLosingEvent() throws Exception {
        String target = decision("/fixture/race", "Target", null).eventId();
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<String> contender = () -> {
                start.await();
                try {

                    return decision("/fixture/race", "Contender", target).eventId();
                } catch (IllegalArgumentException expected) {

                    return "rejected";
                }
            };
            var left = pool.submit(contender);
            var right = pool.submit(contender);
            start.countDown();
            assertThat(List.of(left.get(20, TimeUnit.SECONDS), right.get(20, TimeUnit.SECONDS)))
                    .containsOnlyOnce("rejected");
        }
        assertThat(count("agent_events")).isEqualTo(2);
        assertThat(count("agent_sessions")).isEqualTo(2);
        assertThat(count("decision_replacements")).isEqualTo(1);
        assertThat(count("memory_embeddings")).isEqualTo(2);
    }

    @Test
    void httpAcceptsProjectAndReplacementAndRejectsAmbiguousLegacyScope() throws Exception {
        String target = decision("/fixture/http", "HTTP original", null).eventId();
        var body = mapper.writeValueAsString(request("http-replacement", "/fixture/http", "HTTP replacement", target));
        String response = http.perform(post("/api/decisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String replacement = mapper.readTree(response).path("eventId").asText();
        http.perform(get("/api/recall").param("project", "/fixture/http").param("query", "HTTP"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.items[0].supersedesEventId").value(target));
        http.perform(get("/api/recall").param("scope", target).param("includeSuperseded", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].supersededByEventId").value(replacement));
        http.perform(get("/api/recall").param("scope", "legacy").param("project", "/fixture/http"))
                .andExpect(status().isBadRequest());
        http.perform(get("/api/recall").param("scope", "legacy").param("query", ""))
                .andExpect(status().isBadRequest());
    }

    @Test
    void legacyResponseOmitsUnusedRelationFields() throws Exception {
        String target = decision("/fixture/legacy", "Legacy", null).eventId();
        http.perform(get("/api/recall").param("scope", target))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].supersedesEventId").doesNotExist())
                .andExpect(jsonPath("$.items[0].supersededByEventId").doesNotExist());
        assertThat(mapper.writeValueAsString(request("fixture", "/fixture/legacy", "Legacy", null)))
                .doesNotContain("supersedes");
    }

    private IngestResponse decision(String repo, String text, String supersedes) {

        return captures.captureDecision(request(UUID.randomUUID().toString(), repo, text, supersedes));
    }

    private CaptureDecisionRequest request(String session, String repo, String text, String supersedes) {

        return new CaptureDecisionRequest(
                "manual", session, repo, text, "Verified reason", List.of(), .9, List.of(), supersedes);
    }

    private RecallResult query(String project, String query, boolean history) {

        return recall.recall(null, project, query, 168, List.of("decision"), 50, history);
    }

    private static List<String> ids(RecallResult result) {

        return result.items().stream().map(RecalledItem::eventId).toList();
    }

    private static RecalledItem item(RecallResult result, String id) {

        return result.items().stream()
                .filter(i -> i.eventId().equals(id))
                .findFirst()
                .orElseThrow();
    }

    private long count(String table) {

        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        FixtureEmbedder fixtureEmbedder() {

            return new FixtureEmbedder();
        }
    }

    static class FixtureEmbedder implements TextEmbedder {
        boolean available = true;
        String lastQuery;

        public boolean available() {

            return available;
        }

        public String documentContentHash(String text) {

            return EmbeddingVector.contentHash(text);
        }

        public EmbeddingVector embedDocument(String text) {
            // The selected project's vectors rank below 65 out-of-project vectors. Filtering a
            // global top-50 page afterward would lose both valid project hits.
            if (text.contains("Storage") && !text.matches("(?s).*Storage design [0-9]+.*")) {

                return new EmbeddingVector(model(), new float[] {.8f, .6f});
            }

            return vector();
        }

        public EmbeddingVector embedQuery(String text) {
            lastQuery = text;

            return vector();
        }

        public String model() {

            return "continuity-fixture";
        }

        public int dimensions() {

            return 2;
        }

        private EmbeddingVector vector() {

            return new EmbeddingVector(model(), new float[] {1, 0});
        }
    }
}
