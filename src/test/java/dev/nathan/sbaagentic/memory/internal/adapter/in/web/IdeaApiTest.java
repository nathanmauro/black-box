package dev.nathan.sbaagentic.memory.internal.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nathan.sbaagentic.recording.AgentEvent;
import dev.nathan.sbaagentic.recording.CaptureIdeaRequest;
import dev.nathan.sbaagentic.recording.EventIngestRequest;
import dev.nathan.sbaagentic.recording.EventRecorder;
import dev.nathan.sbaagentic.recording.IngestResponse;
import dev.nathan.sbaagentic.recording.RecordingCaptureOperations;
import dev.nathan.sbaagentic.recording.RecordingCatalog;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** End-to-end coverage of the Idea capture kind's REST surface on an isolated temp database. */
@SpringBootTest(
        properties = {
            "spring.datasource.url=jdbc:sqlite:${java.io.tmpdir}/bb-idea-api-test-${random.uuid}.db",
            "sba.local-ai.enabled=false",
            "sba.elasticsearch.enabled=false",
            "sba.memory.embedding.enabled=false"
        })
@AutoConfigureMockMvc
class IdeaApiTest {

    static final String LANES_BOARD = """
            [Idea] A live lanes board: one lane per project, with items routed to their lane and cross-listed in others.
            - origin: nathan-aside, 2026-09-28 ~15:20 ET, in the ~/bin session 6a060210. Verbatim: "I'm starting to visualize a live board with lanes for all my projects ...".
            - What: a live board with one swimlane per project. Every incoming event ... is routed to its home lane.
            - Prior art:
              - Constellate's idea sky ...
            - legs: 8
            - connects: Constellate/Orbit (NAT-196 In Review), Black Box project identity and routing, the Idea/Evidence kinds (which need primary and secondary lane fields)
            - status: untouched. Nathan: "I'm just dumping it out.\"""";

    static final String EVIDENCE_KIND = """
            [Idea] Black Box Evidence capture kind.
            - origin: nathan-aside, ... Verbatim: "maybe evidence should be captured too".
            - What: make Evidence a first-class capture kind next to the new Idea kind. ...
            - legs: 7
            - connects: Black Box Idea kind, human-turn-first, verification-before-completion habits
            - status: untouched. Planned for a separate Black Box "additions" session.""";

    static final String COCKPIT_DIGEST = """
            [Idea] Cockpit lane digest
            - origin: joint. Verbatim: "digest the lanes every morning".
            - What: a morning digest per lane.
            - legs: 12345678901
            - connects: Cockpit morning digest
            - status: untouched""";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    RecordingCaptureOperations captureOperations;

    @Autowired
    RecordingCatalog catalog;

    @Autowired
    EventRecorder recorder;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void capturePostValidatesAndStoresIdea() throws Exception {
        String repo = uniqueRepo("capture");
        JsonNode body = json(mockMvc.perform(postJson("/api/ideas", """
                        {"source":"claude","clientSessionId":"idea-http","repo":"%s",
                         "title":"Lanes board","oneLiner":"One swimlane per project.","origin":"nathan-aside",
                         "quote":"a live board with lanes","legs":8,"connects":["Orbit (NAT-196)"],
                         "resumeStep":"sketch lanes"}
                        """.formatted(repo)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventType").value("Idea")));

        AgentEvent stored = catalog.findEventById(body.path("eventId").asText()).orElseThrow();
        assertThat(stored.eventType()).isEqualTo("Idea");
        assertThat(stored.text()).startsWith("[Idea] Lanes board — One swimlane per project.");
        assertThat(stored.metadata())
                .containsEntry("kind", "idea")
                .containsEntry("origin", "human-aside")
                .containsEntry("status", "untouched")
                .containsEntry("legs", 8)
                .containsEntry("ideaKey", repo.substring(repo.lastIndexOf('/') + 1) + "-lanes-board");

        mockMvc.perform(postJson("/api/ideas", """
                        {"source":"claude","clientSessionId":"idea-http","title":"x","oneLiner":"y","origin":"daydream"}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.type").value("invalid_argument"))
                .andExpect(jsonPath("$.error.message")
                        .value("origin 'daydream' is not allowed; use one of: human-aside, agent-proposed, joint."));
        mockMvc.perform(postJson("/api/ideas", """
                        {"source":"claude","clientSessionId":"idea-http","title":"x","oneLiner":"y","origin":"joint",
                         "status":"shipped"}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.type").value("invalid_argument"));
        mockMvc.perform(postJson("/api/ideas", """
                        {"source":"claude","clientSessionId":"idea-http","title":"x","oneLiner":"y","origin":"joint",
                         "legs":11}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.type").value("validation_failed"));
        mockMvc.perform(postJson("/api/ideas", """
                        {"source":"claude","clientSessionId":"idea-http","oneLiner":"y","origin":"joint"}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.type").value("validation_failed"));
    }

    @Test
    void listCollapsesToLatestPerIdeaKeyAndFilters() throws Exception {
        String repo = uniqueRepo("list");
        String other = uniqueRepo("other");
        capture(repo, "Lanes board", "human-aside", null, 5);
        capture(repo, "Evidence kind", "agent-proposed", null, 7);
        IngestResponse latest = capture(repo, "Lanes board", "human-aside", "tracked", 8);
        capture(other, "Unrelated", "agent-proposed", null, 1);

        JsonNode all =
                json(mockMvc.perform(get("/api/ideas").param("repo", repo)).andExpect(status().isOk()));
        assertThat(all.path("count").asInt()).isEqualTo(2);
        JsonNode lanes = all.path("items").get(0);
        assertThat(lanes.path("title").asText()).isEqualTo("Lanes board");
        assertThat(lanes.path("eventId").asText()).isEqualTo(latest.eventId());
        assertThat(lanes.path("status").asText()).isEqualTo("tracked");
        assertThat(lanes.path("legs").asInt()).isEqualTo(8);
        assertThat(lanes.path("revisions").asInt()).isEqualTo(2);
        assertThat(Instant.parse(lanes.path("firstCapturedAt").asText()))
                .isBefore(Instant.parse(lanes.path("capturedAt").asText()));
        assertThat(lanes.path("migratedFrom").isNull()).isTrue();
        assertThat(lanes.path("sessionId").asText()).isNotBlank();
        assertThat(all.path("items").get(1).path("title").asText()).isEqualTo("Evidence kind");
        assertThat(all.path("items").get(1).path("revisions").asInt()).isEqualTo(1);

        assertThat(titles(get("/api/ideas").param("repo", repo).param("status", "untouched")))
                .containsExactly("Evidence kind");
        assertThat(titles(get("/api/ideas").param("repo", repo).param("status", "untouched,tracked")))
                .containsExactly("Lanes board", "Evidence kind");
        assertThat(titles(get("/api/ideas")
                        .param("repo", repo)
                        .param("status", "tracked")
                        .param("status", "untouched")))
                .containsExactly("Lanes board", "Evidence kind");
        assertThat(titles(get("/api/ideas").param("repo", repo).param("origin", "agent-proposed")))
                .containsExactly("Evidence kind");
        assertThat(titles(get("/api/ideas").param("repo", repo).param("origin", "nathan-aside")))
                .containsExactly("Lanes board");
        assertThat(titles(get("/api/ideas").param("project", repo + "/").param("q", "EVIDENCE kind")))
                .containsExactly("Evidence kind");
        assertThat(titles(get("/api/ideas").param("repo", repo).param("limit", "1")))
                .containsExactly("Lanes board");
        assertThat(titles(get("/api/ideas").param("repo", repo).param("limit", "0")))
                .containsExactly("Lanes board");
        assertThat(titles(get("/api/ideas").param("repo", repo).param("limit", "100000")))
                .containsExactly("Lanes board", "Evidence kind");
        assertThat(titles(get("/api/ideas").param("repo", other))).containsExactly("Unrelated");
        assertThat(titles(get("/api/ideas").param("q", "Unrelated"))).containsExactly("Unrelated");

        mockMvc.perform(get("/api/ideas").param("status", "someday"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.type").value("invalid_argument"));
        mockMvc.perform(get("/api/ideas").param("origin", "hallway"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.type").value("invalid_argument"));
    }

    @Test
    void genericIdeaEventsWithoutStructuredFieldsStillList() throws Exception {
        String repo = uniqueRepo("generic");
        recorder.ingest(new EventIngestRequest(
                "chatgpt-work",
                "chatgpt-capture-fixture",
                null,
                "Idea",
                "assistant",
                "[Idea] Free-text idea\nCaptured through the generic event route.",
                repo,
                null,
                null,
                null,
                Map.of("kind", "idea", "repo", repo),
                null));

        JsonNode listed =
                json(mockMvc.perform(get("/api/ideas").param("repo", repo)).andExpect(status().isOk()));
        assertThat(listed.path("count").asInt()).isEqualTo(1);
        JsonNode idea = listed.path("items").get(0);
        assertThat(idea.path("title").asText()).isEqualTo("Free-text idea");
        assertThat(idea.path("status").asText()).isEqualTo("untouched");
        assertThat(idea.path("origin").isNull()).isTrue();
        assertThat(idea.path("ideaKey").asText()).endsWith("-free-text-idea");
        assertThat(idea.path("revisions").asInt()).isEqualTo(1);
    }

    @Test
    void migrationDryRunWritesNothingAndApplyIsIdempotent() throws Exception {
        String repo = uniqueRepo("migration");
        String otherRepo = uniqueRepo("migration-other");
        String lanesId = captureOperations
                .captureObservation("claude", "claude-idea-observations", repo, LANES_BOARD)
                .eventId();
        String evidenceId = captureOperations
                .captureObservation("claude", "claude-idea-observations", repo, EVIDENCE_KIND)
                .eventId();
        captureOperations.captureObservation("claude", "claude-idea-observations", repo, "Not an [Idea] observation");
        // Newest observation, from a second repo: a single shared migration session would end with
        // this repo as its cwd and misfile the first repo's ideas in recall and project scoping.
        String digestId = captureOperations
                .captureObservation("claude", "claude-other-observations", otherRepo, COCKPIT_DIGEST)
                .eventId();
        AgentEvent digestObservation = catalog.findEventById(digestId).orElseThrow();
        // A native capture of the same idea, newer than its observation, must stay the latest state.
        IngestResponse nativeDigest = captureOperations.captureIdea(new CaptureIdeaRequest(
                "claude",
                "claude-native-ideas",
                otherRepo,
                "Cockpit lane digest",
                "A morning digest per lane.",
                "joint",
                null,
                null,
                null,
                "tracked",
                null,
                null,
                null,
                null,
                null));
        AgentEvent lanesBefore = catalog.findEventById(lanesId).orElseThrow();
        long eventsBefore = eventCount();

        JsonNode dryRun =
                json(mockMvc.perform(post("/api/ideas/migrate-observations")).andExpect(status().isOk()));
        assertThat(eventCount()).isEqualTo(eventsBefore);
        assertThat(dryRun.path("apply").asBoolean()).isFalse();
        assertThat(dryRun.path("created").asInt()).isZero();
        assertThat(dryRun.path("skipped").asInt()).isZero();
        assertThat(dryRun.path("candidates")).hasSize(3);
        JsonNode digestCandidate = dryRun.path("candidates").get(0);
        assertThat(digestCandidate.path("observationId").asText()).isEqualTo(digestId);
        assertThat(digestCandidate.path("warnings").toString()).contains("legs 12345678901 is outside 0..10: dropped");
        assertThat(digestCandidate.path("idea").path("clientSessionId").asText())
                .isEqualTo("idea-migration:" + otherRepo);
        JsonNode evidenceCandidate = dryRun.path("candidates").get(1);
        assertThat(evidenceCandidate.path("observationId").asText()).isEqualTo(evidenceId);
        assertThat(evidenceCandidate.path("sessionId").asText()).isEqualTo(lanesBefore.sessionId());
        assertThat(evidenceCandidate.path("alreadyMigrated").asBoolean()).isFalse();
        assertThat(evidenceCandidate.path("createdEventId").isNull()).isTrue();
        assertThat(evidenceCandidate.path("warnings")).isEmpty();
        JsonNode evidenceIdea = evidenceCandidate.path("idea");
        assertThat(evidenceIdea.path("title").asText()).isEqualTo("Black Box Evidence capture kind.");
        assertThat(evidenceIdea.path("origin").asText()).isEqualTo("human-aside");
        assertThat(evidenceIdea.path("legs").asInt()).isEqualTo(7);
        assertThat(evidenceIdea.path("sourceRef").asText()).isEqualTo(lanesBefore.sessionId());
        assertThat(evidenceIdea.path("repo").asText()).isEqualTo(repo);
        assertThat(evidenceIdea.path("notes").asText()).isEqualTo(EVIDENCE_KIND);
        assertThat(evidenceIdea.path("clientSessionId").asText()).isEqualTo("idea-migration:" + repo);
        assertThat(dryRun.path("candidates").get(2).path("idea").path("connects"))
                .hasSize(3);

        JsonNode applied =
                json(mockMvc.perform(post("/api/ideas/migrate-observations").param("apply", "true"))
                        .andExpect(status().isOk()));
        assertThat(applied.path("apply").asBoolean()).isTrue();
        assertThat(applied.path("created").asInt()).isEqualTo(3);
        assertThat(applied.path("skipped").asInt()).isZero();
        assertThat(eventCount()).isEqualTo(eventsBefore + 3);
        List<String> createdIds = new ArrayList<>();
        applied.path("candidates").forEach(candidate -> {
            assertThat(candidate.path("createdEventId").isTextual()).isTrue();
            createdIds.add(candidate.path("createdEventId").asText());
        });

        JsonNode listed =
                json(mockMvc.perform(get("/api/ideas").param("repo", repo)).andExpect(status().isOk()));
        assertThat(listed.path("count").asInt()).isEqualTo(2);
        List<String> migratedFrom = new ArrayList<>();
        listed.path("items").forEach(item -> {
            migratedFrom.add(item.path("migratedFrom").asText());
            assertThat(createdIds).contains(item.path("eventId").asText());
            assertThat(item.path("status").asText()).isEqualTo("untouched");
            assertThat(item.path("clientSessionId").asText()).isEqualTo("idea-migration:" + repo);
        });
        assertThat(migratedFrom).containsExactly(evidenceId, lanesId);
        assertThat(Instant.parse(listed.path("items").get(1).path("capturedAt").asText()))
                .isEqualTo(lanesBefore.observedAt());
        assertThat(listed.path("items").get(1).path("quote").asText())
                .isEqualTo("I'm starting to visualize a live board with lanes for all my projects ...");

        JsonNode again =
                json(mockMvc.perform(post("/api/ideas/migrate-observations").param("apply", "true"))
                        .andExpect(status().isOk()));
        assertThat(again.path("created").asInt()).isZero();
        assertThat(again.path("skipped").asInt()).isEqualTo(3);
        assertThat(eventCount()).isEqualTo(eventsBefore + 3);
        again.path("candidates").forEach(candidate -> {
            assertThat(candidate.path("alreadyMigrated").asBoolean()).isTrue();
            assertThat(createdIds).contains(candidate.path("createdEventId").asText());
        });

        // The migrated digest is backdated to its observation, so the newer native status wins.
        JsonNode digest = json(mockMvc.perform(get("/api/ideas").param("repo", otherRepo))
                        .andExpect(status().isOk()))
                .path("items");
        assertThat(digest).hasSize(1);
        assertThat(digest.get(0).path("eventId").asText()).isEqualTo(nativeDigest.eventId());
        assertThat(digest.get(0).path("status").asText()).isEqualTo("tracked");
        assertThat(digest.get(0).path("revisions").asInt()).isEqualTo(2);
        assertThat(Instant.parse(digest.get(0).path("firstCapturedAt").asText()))
                .isEqualTo(digestObservation.observedAt());

        // Each repo's migrated ideas live in a session whose cwd is that repo.
        assertThat(sessionCwd("idea-migration:" + repo)).isEqualTo(repo);
        assertThat(sessionCwd("idea-migration:" + otherRepo)).isEqualTo(otherRepo);
        String digestMigratedId =
                applied.path("candidates").get(0).path("createdEventId").asText();
        List<String> repoRecall = recalledIdeaIds(repo);
        assertThat(repoRecall).contains(createdIds.get(1), createdIds.get(2)).doesNotContain(digestMigratedId);
        List<String> otherRecall = recalledIdeaIds(otherRepo);
        assertThat(otherRecall).contains(digestMigratedId).doesNotContain(createdIds.get(1), createdIds.get(2));

        AgentEvent lanesAfter = catalog.findEventById(lanesId).orElseThrow();
        assertThat(lanesAfter).isEqualTo(lanesBefore);
        assertThat(lanesAfter.eventType()).isEqualTo("Observation");
    }

    private IngestResponse capture(String repo, String title, String origin, String status, Integer legs) {

        return captureOperations.captureIdea(new CaptureIdeaRequest(
                "codex",
                "codex-ideas",
                repo,
                title,
                title + " in one line.",
                origin,
                null,
                null,
                legs,
                status,
                null,
                null,
                null,
                null,
                null));
    }

    private List<String> titles(MockHttpServletRequestBuilder request) throws Exception {
        JsonNode body = json(mockMvc.perform(request).andExpect(status().isOk()));
        List<String> titles = new ArrayList<>();
        body.path("items").forEach(item -> titles.add(item.path("title").asText()));
        assertThat(body.path("count").asInt()).isEqualTo(titles.size());

        return titles;
    }

    private static MockHttpServletRequestBuilder postJson(String path, String json) {

        return post(path).contentType(MediaType.APPLICATION_JSON).content(json);
    }

    private JsonNode json(org.springframework.test.web.servlet.ResultActions actions) throws Exception {

        return objectMapper.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    private List<String> recalledIdeaIds(String scope) throws Exception {
        JsonNode recalled =
                json(mockMvc.perform(get("/api/recall").param("scope", scope).param("kinds", "idea"))
                        .andExpect(status().isOk()));
        List<String> ids = new ArrayList<>();
        recalled.path("items").forEach(item -> ids.add(item.path("eventId").asText()));

        return ids;
    }

    private String sessionCwd(String clientSessionId) {

        return jdbcTemplate.queryForObject(
                "SELECT cwd FROM agent_sessions WHERE source = 'claude' AND client_session_id = ?",
                String.class,
                clientSessionId);
    }

    private long eventCount() {
        Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM agent_events", Long.class);

        return count == null ? 0 : count;
    }

    private static String uniqueRepo(String label) {

        return "/tmp/idea-api-" + label + "-" + UUID.randomUUID();
    }
}
