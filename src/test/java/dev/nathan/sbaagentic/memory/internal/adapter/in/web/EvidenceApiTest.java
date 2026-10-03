package dev.nathan.sbaagentic.memory.internal.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nathan.sbaagentic.recording.EventIngestRequest;
import dev.nathan.sbaagentic.recording.EventRecorder;
import dev.nathan.sbaagentic.recording.RecordingCaptureOperations;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
        properties = {
            "spring.datasource.url=jdbc:sqlite:${java.io.tmpdir}/bb-evidence-api-test-${random.uuid}.db",
            "sba.local-ai.enabled=false",
            "sba.summary.backend=local",
            "sba.judge.enabled=false",
            "sba.elasticsearch.enabled=false",
            "sba.memory.embedding.enabled=false"
        })
@AutoConfigureMockMvc
class EvidenceApiTest {
    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    @Qualifier("agenticToolCallbacks")
    ToolCallbackProvider callbacks;

    @Autowired
    RecordingCaptureOperations captureOperations;

    @Autowired
    EventRecorder recorder;

    @Test
    void captureListFilterAndRecallAcrossIdeaRevisions() throws Exception {
        String repo = "/tmp/evidence-" + UUID.randomUUID();
        String key = "idea-" + UUID.randomUUID();
        JsonNode first = captureJson("/api/ideas", """
                {"source":"codex","clientSessionId":"first","repo":"%s","title":"Lane idea",
                 "oneLiner":"Keep lane state","origin":"joint","ideaKey":"%s",
                 "project":"Board","alsoIn":[{"project":"Other","score":0.8}]}
                """.formatted(repo, key));
        String revision = first.path("eventId").asText();
        captureJson("/api/ideas", """
                {"source":"codex","clientSessionId":"second","repo":"%s","title":"Lane idea",
                 "oneLiner":"Keep lane state","origin":"joint","ideaKey":"%s","status":"tracked"}
                """.formatted(repo, key));
        mvc.perform(get("/api/ideas").param("repo", repo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].project").value("Board"))
                .andExpect(jsonPath("$.items[0].alsoIn[0].project").value("Other"));

        captureJson("/api/evidence", """
                {"source":"codex","clientSessionId":"evidence","repo":"%s","claim":"The idea was said",
                 "sourceRef":"session:line","excerpt":"verbatim proof","supports":["idea:%s"],
                 "project":"Board","alsoIn":[{"project":"Other","score":0.4}]}
                """.formatted(repo, key));
        captureJson("/api/evidence", """
                {"source":"codex","clientSessionId":"evidence","repo":"%s","claim":"Earlier revision failed",
                 "sourceRef":"run 2","refutes":["event:%s"]}
                """.formatted(repo, revision.substring(0, 8)));
        mvc.perform(get("/api/evidence").param("repo", repo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(2));
        mvc.perform(get("/api/evidence").param("repo", repo).param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1));
        mvc.perform(get("/api/evidence").param("project", repo).param("target", "idea:" + key))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1));
        mvc.perform(get("/api/evidence").param("q", "earlier revision").param("repo", repo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1));
        mvc.perform(get("/api/evidence").param("target", "invalid target"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.type").value("invalid_argument"))
                .andExpect(jsonPath("$.error.message")
                        .value(org.hamcrest.Matchers.containsString("target entry 'invalid target'")));
        mvc.perform(get("/api/evidence").param("target", " ").param("repo", repo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(2));

        String foreignKey = "foreign-" + UUID.randomUUID();
        String foreignRevision = captureJson("/api/ideas", """
                {"source":"codex","clientSessionId":"foreign","repo":"%s","title":"Foreign idea",
                 "oneLiner":"Unrelated","origin":"joint","ideaKey":"%s"}
                """.formatted(repo, foreignKey))
                .path("eventId")
                .asText();
        String observationId = captureOperations
                .captureObservation("codex", "unrelated-event", repo, "Unrelated observation")
                .eventId();
        captureJson(
                "/api/evidence", """
                {"source":"codex","clientSessionId":"evidence","repo":"%s","claim":"Uppercase revision link",
                 "sourceRef":"run 3","supports":["event:%s"]}
                """.formatted(repo, revision.substring(0, 8).toUpperCase()));
        captureJson("/api/evidence", """
                {"source":"codex","clientSessionId":"evidence","repo":"%s","claim":"Other key",
                 "sourceRef":"run 4","supports":["idea:%s-x"]}
                """.formatted(repo, key));
        captureJson("/api/evidence", """
                {"source":"codex","clientSessionId":"evidence","repo":"%s","claim":"Other revision",
                 "sourceRef":"run 5","supports":["event:%s"]}
                """.formatted(repo, foreignRevision));
        captureJson("/api/evidence", """
                {"source":"codex","clientSessionId":"evidence","repo":"%s","claim":"Observation event",
                 "sourceRef":"run 6","supports":["event:%s"]}
                """.formatted(repo, observationId));
        mvc.perform(get("/api/ideas/detail").param("ideaKey", key))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idea.status").value("tracked"))
                .andExpect(jsonPath("$.supports[0].claim").value("Uppercase revision link"))
                .andExpect(jsonPath("$.supports.length()").value(2))
                .andExpect(jsonPath("$.supports[1].claim").value("The idea was said"))
                .andExpect(jsonPath("$.refutes.length()").value(1))
                .andExpect(jsonPath("$.refutes[0].claim").value("Earlier revision failed"));
        mvc.perform(get("/api/ideas/detail").param("ideaKey", "missing-" + key))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.type").value("request_failed"));
    }

    @Test
    void mcpCallbacksCaptureAndRecallLinkedEvidence() {
        String key = "mcp-" + UUID.randomUUID();
        callback("captureIdea").call("""
                {"source":"codex","clientSessionId":"mcp-roundtrip","title":"MCP idea",
                 "oneLiner":"Keep this thought","origin":"joint","ideaKey":"%s"}
                """.formatted(key));
        callback("captureEvidence").call("""
                {"source":"codex","clientSessionId":"mcp-roundtrip","claim":"MCP captured the fact",
                 "sourceRef":"callback run","supports":["idea:%s"]}
                """.formatted(key));
        String detail = callback("recallIdea").call("{\"ideaKey\":\"" + key + "\"}");
        assertThat(detail).contains("MCP idea", "MCP captured the fact");
        assertThatThrownBy(() -> callback("recallIdea").call("{\"ideaKey\":\"missing-" + key + "\"}"))
                .hasMessageContaining("list ideas or check the ideaKey");
    }

    @Test
    void emptyLaneRecaptureRetainsEarlierLanesAndNewHomeRemovesConflict() throws Exception {
        String repo = "/tmp/lane-" + UUID.randomUUID();
        String key = "lane-" + UUID.randomUUID();
        captureJson("/api/ideas", """
                {"source":"codex","clientSessionId":"lanes-1","repo":"%s","title":"Lane idea",
                 "oneLiner":"Keep lanes","origin":"joint","ideaKey":"%s",
                 "project":"Old home","alsoIn":[{"project":"New home","score":0.8}]}
                """.formatted(repo, key));
        captureJson("/api/ideas", """
                {"source":"codex","clientSessionId":"lanes-2","repo":"%s","title":"Lane idea",
                 "oneLiner":"Keep lanes","origin":"joint","ideaKey":"%s",
                 "status":"tracked","alsoIn":[]}
                """.formatted(repo, key));
        mvc.perform(get("/api/ideas/detail").param("ideaKey", key))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idea.project").value("Old home"))
                .andExpect(jsonPath("$.idea.alsoIn[0].project").value("New home"));
        captureJson("/api/ideas", """
                {"source":"codex","clientSessionId":"lanes-3","repo":"%s","title":"Lane idea",
                 "oneLiner":"Keep lanes","origin":"joint","ideaKey":"%s",
                 "project":" new HOME ","alsoIn":[]}
                """.formatted(repo, key));
        mvc.perform(get("/api/ideas/detail").param("ideaKey", key))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idea.project").value("new HOME"))
                .andExpect(jsonPath("$.idea.alsoIn").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void evidenceViewDropsSecondaryLaneThatMatchesHome() throws Exception {
        String repo = "/tmp/view-lane-" + UUID.randomUUID();
        recorder.ingest(new EventIngestRequest(
                "chatgpt-work",
                "raw-lane",
                null,
                "Evidence",
                "assistant",
                "[Evidence] Raw lane evidence",
                repo,
                null,
                null,
                null,
                Map.of(
                        "project",
                        " Board ",
                        "alsoIn",
                        List.of(Map.of("project", "board", "score", 0.8), Map.of("project", "Other", "score", 0.3))),
                null));
        mvc.perform(get("/api/evidence").param("repo", repo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].project").value(" Board "))
                .andExpect(jsonPath("$.items[0].alsoIn.length()").value(1))
                .andExpect(jsonPath("$.items[0].alsoIn[0].project").value("Other"));
    }

    @Test
    void observedAtAndGatewayTextHaveSeparateCaptureTimeAndSearchableText() throws Exception {
        String repo = "/tmp/raw-evidence-" + UUID.randomUUID();
        Instant before = Instant.now();
        captureJson("/api/evidence", """
                {"source":"codex","clientSessionId":"time","repo":"%s","claim":"Time fact",
                 "sourceRef":"run","observedAt":"2020-01-01T00:00:00Z","supports":[],"refutes":[]}
                """.formatted(repo));
        JsonNode listed = mapper.readTree(mvc.perform(get("/api/evidence").param("repo", repo))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
        JsonNode fact = listed.path("items").get(0);
        assertThat(fact.path("observedAt").asText()).isEqualTo("2020-01-01T00:00:00Z");
        assertThat(Instant.parse(fact.path("capturedAt").asText())).isAfterOrEqualTo(before);
        assertThat(fact.path("supports").isNull()).isTrue();
        assertThat(fact.path("refutes").isNull()).isTrue();

        recorder.ingest(new EventIngestRequest(
                "chatgpt-work",
                "gateway-evidence",
                null,
                "Evidence",
                "assistant",
                "[Evidence] Gateway claim\nSecond-line-unique command proof",
                repo,
                null,
                null,
                null,
                Map.of(),
                null));
        mvc.perform(get("/api/evidence").param("repo", repo).param("q", "second-line-unique"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.items[0].claim").value("Gateway claim"));
        mvc.perform(get("/api/recall")
                        .param("project", repo)
                        .param("query", "second-line-unique")
                        .param("kinds", "evidence"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].body")
                        .value("[Evidence] Gateway claim\nSecond-line-unique command proof"));
        mvc.perform(get("/api/evidence").param("repo", repo).param("q", "gateway claim"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1));

        captureJson("/api/evidence", """
                {"source":"codex","clientSessionId":"blank-time","repo":"%s","claim":"Blank time",
                 "sourceRef":"run","observedAt":"   "}
                """.formatted(repo));
        mvc.perform(get("/api/evidence").param("repo", repo).param("target", " "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(3));
    }

    private ToolCallback callback(String name) {
        for (ToolCallback callback : callbacks.getToolCallbacks()) {
            if (callback.getToolDefinition().name().equals(name)) {

                return callback;
            }
        }
        throw new IllegalArgumentException("Unknown tool: " + name);
    }

    @Test
    void invalidEvidenceIsRejected() throws Exception {
        mvc.perform(post("/api/evidence")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                {"source":"codex","clientSessionId":"c","claim":"fact","sourceRef":"run",
                 "observedAt":"yesterday"}
                """))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/evidence")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                {"source":"codex","clientSessionId":"c","claim":"fact","sourceRef":"run",
                 "supports":["idea:x"],"refutes":["idea:x"]}
                """))
                .andExpect(status().isBadRequest());
    }

    private JsonNode captureJson(String path, String body) throws Exception {
        String result = mvc.perform(
                        post(path).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode response = mapper.readTree(result);
        assertThat(response.path("eventId").asText()).isNotBlank();

        return response;
    }
}
