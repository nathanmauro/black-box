package dev.nathan.sbaagentic.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nathan.sbaagentic.project.ProjectKey;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
        properties = {
            "spring.datasource.url=jdbc:sqlite:${java.io.tmpdir}/bb-project-graph-api-test-${random.uuid}.db",
            "sba.local-ai.enabled=false",
            "sba.summary.backend=local",
            "sba.elasticsearch.enabled=false",
            "sba.ask.embedding-enabled=false",
            "sba.memory.embedding.enabled=false"
        })
@AutoConfigureMockMvc
class ProjectGraphApiTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetDatabase() {
        jdbcTemplate.update("DELETE FROM task_events");
        jdbcTemplate.update("DELETE FROM tasks");
        jdbcTemplate.update("DELETE FROM specs");
        jdbcTemplate.update("DELETE FROM session_meld_inputs");
        jdbcTemplate.update("DELETE FROM session_melds");
        jdbcTemplate.update("DELETE FROM agent_events");
        jdbcTemplate.update("DELETE FROM agent_sessions");
        jdbcTemplate.update("DELETE FROM project_aliases");
    }

    @Test
    void graphFeedReturnsCapturesAndTasks() throws Exception {
        String key = UUID.randomUUID().toString().replace("-", "");
        String cwd = "/tmp/black-box-graph-alpha-" + key;

        mockMvc.perform(post("/api/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "source": "codex",
                                  "clientSessionId": "graph-%s-decision",
                                  "eventType": "Decision",
                                  "role": "assistant",
                                  "text": "Decision body",
                                  "cwd": "%s",
                                  "metadata": {
                                    "kind": "decision",
                                    "decision": "Keep trajectory assembly in the browser",
                                    "rationale": "The heuristics will move quickly.",
                                    "alternatives": ["Assemble SVG nodes server-side"],
                                    "confidence": 0.88,
                                    "openLoops": ["Add the frontend pure builder"]
                                  },
                                  "observedAt": "2026-08-05T10:00:00Z"
                                }
                                """.formatted(key, cwd)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "source": "codex",
                                  "clientSessionId": "graph-%s-observation",
                                  "eventType": "Observation",
                                  "role": "assistant",
                                  "text": "Timeline evidence is available.",
                                  "cwd": "%s",
                                  "metadata": {"kind": "observation"},
                                  "observedAt": "2026-08-05T10:01:00Z"
                                }
                                """.formatted(key, cwd)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "source": "claude",
                                  "clientSessionId": "graph-%s-handoff",
                                  "eventType": "Handoff",
                                  "role": "assistant",
                                  "text": "Handoff body",
                                  "cwd": "%s",
                                  "metadata": {
                                    "kind": "handoff",
                                    "contextSummary": "Backend feed is ready for frontend graphing.",
                                    "toAgent": "frontend",
                                    "openLoops": ["Render futures"],
                                    "nextAction": "Build the SVG graph view"
                                  },
                                  "observedAt": "2026-08-05T10:02:00Z"
                                }
                                """.formatted(key, cwd)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "source": "codex",
                                  "clientSessionId": "graph-%s-projection",
                                  "eventType": "Projection",
                                  "role": "assistant",
                                  "text": "Projection body",
                                  "cwd": "%s",
                                  "metadata": {
                                    "kind": "projection",
                                    "paths": [
                                      {
                                        "title": "Ship the trajectory tab",
                                        "description": "Render the fact feed as a pure SVG graph.",
                                        "confidence": 0.67
                                      }
                                    ]
                                  },
                                  "observedAt": "2026-08-05T10:03:00Z"
                                }
                                """.formatted(key, cwd)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "source": "codex",
                                  "clientSessionId": "graph-%s-noise",
                                  "eventType": "AssistantMessage",
                                  "role": "assistant",
                                  "text": "Implementation chatter should not become a milestone.",
                                  "cwd": "%s",
                                  "observedAt": "2026-08-05T10:01:30Z"
                                }
                                """.formatted(key, cwd)))
                .andExpect(status().isOk());

        String specBody = mockMvc.perform(post("/api/specs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "projectKey", cwd,
                                "title", "Trajectory graph",
                                "body", "Build the frontend graph once the backend feed exists.",
                                "actor", "planner"))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String specId = objectMapper.readTree(specBody).path("id").asText();

        String openTaskId = enqueueTask(specId, "Build trajectory graph view", 7);
        String blockedTaskId = enqueueTask(specId, "Blocked graph work", 8);
        String claimedTaskId = enqueueTask(specId, "Claimed graph work", 6);
        String inProgressTaskId = enqueueTask(specId, "In-progress graph work", 9);
        String doneTaskId = enqueueTask(specId, "Done graph work", 5);
        String cancelledTaskId = enqueueTask(specId, "Cancelled graph work", 4);

        setTaskStatus(openTaskId, "open", "2026-08-05T10:04:00Z");
        setTaskStatus(blockedTaskId, "blocked", "2026-08-05T10:05:00Z");
        setTaskStatus(claimedTaskId, "claimed", "2026-08-05T10:06:00Z");
        setTaskStatus(inProgressTaskId, "in_progress", "2026-08-05T10:07:00Z");
        setTaskStatus(doneTaskId, "done", "2026-08-05T10:08:00Z");
        setTaskStatus(cancelledTaskId, "cancelled", "2026-08-05T10:09:00Z");

        JsonNode project = projectByCanonicalKey(
                objectMapper.readTree(mockMvc.perform(get("/api/projects"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString()),
                cwd);
        assertThat(project).isNotNull();
        String projectKey = project.path("projectKey").asText();

        List<String> sessionIds = textValues(
                objectMapper.readTree(mockMvc.perform(get("/api/projects/{projectKey}/sessions", projectKey))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString()),
                "id");
        String meldBody = mockMvc.perform(post("/api/melds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "projectKey", projectKey,
                                "title", "Trajectory synthesis meld",
                                "body", "Saved graph synthesis.",
                                "provider", "local",
                                "model", "context-bundle",
                                "executionMode", "export_bundle",
                                "savedFromPreview", true,
                                "sessionIds", sessionIds))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String meldId = objectMapper.readTree(meldBody).path("id").asText();
        jdbcTemplate.update("UPDATE session_melds SET created_at = ? WHERE id = ?", "2026-08-05T10:04:00Z", meldId);

        String graphBody = mockMvc.perform(get("/api/projects/{projectKey}/graph", projectKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectKey").value(projectKey))
                .andExpect(jsonPath("$.canonicalKey").value(cwd))
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode graph = objectMapper.readTree(graphBody);

        assertThat(graph.path("generatedAt").asText()).isNotBlank();
        assertThat(graph.path("totalCaptures").asLong()).isEqualTo(5);
        assertThat(graph.path("captures").size()).isEqualTo(5);
        assertThat(textValues(graph.path("captures"), "clientSessionId"))
                .doesNotContain("graph-%s-noise".formatted(key));
        assertThat(graph.path("captures").get(0).path("id").asText()).isEqualTo(meldId);
        assertThat(graph.path("captures").get(0).path("kind").asText()).isEqualTo("meld");
        assertThat(graph.path("captures").get(0).path("headline").asText()).isEqualTo("Trajectory synthesis meld");
        assertThat(graph.path("captures").get(1).path("kind").asText()).isEqualTo("projection");
        assertThat(captureByKind(graph, "decision").path("rationale").asText())
                .isEqualTo("The heuristics will move quickly.");
        assertThat(captureByKind(graph, "decision").path("alternatives").get(0).asText())
                .isEqualTo("Assemble SVG nodes server-side");
        assertThat(captureByKind(graph, "handoff").path("nextAction").asText()).isEqualTo("Build the SVG graph view");
        assertThat(captureByKind(graph, "projection")
                        .path("paths")
                        .get(0)
                        .path("title")
                        .asText())
                .isEqualTo("Ship the trajectory tab");

        assertThat(graph.path("tasks").size()).isEqualTo(4);
        assertThat(textValues(graph.path("tasks"), "id"))
                .contains(openTaskId, blockedTaskId, claimedTaskId, inProgressTaskId)
                .doesNotContain(doneTaskId, cancelledTaskId);
        assertThat(textValues(graph.path("tasks"), "status"))
                .contains("open", "blocked", "claimed", "in_progress")
                .doesNotContain("done", "cancelled");
        JsonNode task = taskById(graph, openTaskId);
        assertThat(task.path("title").asText()).isEqualTo("Build trajectory graph view");
        assertThat(task.path("status").asText()).isEqualTo("open");
        assertThat(task.path("priority").asInt()).isEqualTo(7);

        String unknownCanonical = cwd + "-unknown";
        String unknownKey = ProjectKey.of(unknownCanonical).encoded();
        mockMvc.perform(get("/api/projects/{projectKey}/timeline", unknownKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
        mockMvc.perform(get("/api/projects/{projectKey}/graph", unknownKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canonicalKey").value(unknownCanonical))
                .andExpect(jsonPath("$.totalCaptures").value(0))
                .andExpect(jsonPath("$.captures.length()").value(0))
                .andExpect(jsonPath("$.tasks.length()").value(0));
    }

    @Test
    void graphCaptureLimitUsesChronologicalInstantOrderingForVariableWidthTimestamps() throws Exception {
        String key = UUID.randomUUID().toString().replace("-", "");
        String cwd = "/tmp/black-box-graph-order-" + key;
        String sessionId = "session-" + key;
        String clientSessionId = "graph-order-" + key;

        jdbcTemplate.update(
                """
                INSERT INTO agent_sessions
                       (id, source, client_session_id, title, cwd, started_at, last_seen_at, event_count)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                sessionId,
                "codex",
                clientSessionId,
                "Ordering session",
                cwd,
                "2026-08-05T10:00:00Z",
                "2026-08-05T10:00:00.999Z",
                121);
        for (int i = 0; i < 120; i++) {
            jdbcTemplate.update(
                    """
                    INSERT INTO agent_events
                           (id, session_id, source, client_session_id, event_type, role, text, observed_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    "fractional-%03d-%s".formatted(i, key),
                    sessionId,
                    "codex",
                    clientSessionId,
                    "Observation",
                    "assistant",
                    "Fractional milestone " + i,
                    "2026-08-05T10:00:00.999Z");
        }
        String zeroFractionId = "zero-fraction-" + key;
        jdbcTemplate.update(
                """
                INSERT INTO agent_events
                       (id, session_id, source, client_session_id, event_type, role, text, observed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                zeroFractionId,
                sessionId,
                "codex",
                clientSessionId,
                "Observation",
                "assistant",
                "Zero-fraction milestone",
                "2026-08-05T10:00:00Z");

        JsonNode graph = objectMapper.readTree(mockMvc.perform(get(
                        "/api/projects/{projectKey}/graph", ProjectKey.of(cwd).encoded()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());

        assertThat(graph.path("totalCaptures").asLong()).isEqualTo(121);
        assertThat(graph.path("captures").size()).isEqualTo(120);
        assertThat(textValues(graph.path("captures"), "id")).doesNotContain(zeroFractionId);
    }

    private String enqueueTask(String specId, String title, int priority) throws Exception {
        String body = mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "specId", specId,
                                "title", title,
                                "lane", "frontend",
                                "priority", priority,
                                "actor", "planner"))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return objectMapper
                .readTree(body)
                .path("snapshot")
                .path("task")
                .path("id")
                .asText();
    }

    private void setTaskStatus(String taskId, String status, String updatedAt) {
        jdbcTemplate.update("UPDATE tasks SET status = ?, updated_at = ? WHERE id = ?", status, updatedAt, taskId);
    }

    private JsonNode projectByCanonicalKey(JsonNode projects, String canonicalKey) {
        for (JsonNode project : projects) {
            if (canonicalKey.equals(project.path("canonicalKey").asText())) {

                return project;
            }
        }

        return null;
    }

    private List<String> textValues(JsonNode items, String fieldName) {
        java.util.ArrayList<String> values = new java.util.ArrayList<>();
        for (JsonNode item : items) {
            values.add(item.path(fieldName).asText());
        }

        return values;
    }

    private JsonNode captureByKind(JsonNode graph, String kind) {
        for (JsonNode capture : graph.path("captures")) {
            if (kind.equals(capture.path("kind").asText())) {

                return capture;
            }
        }
        throw new AssertionError("Missing capture kind: " + kind);
    }

    private JsonNode taskById(JsonNode graph, String taskId) {
        for (JsonNode task : graph.path("tasks")) {
            if (taskId.equals(task.path("id").asText())) {

                return task;
            }
        }
        throw new AssertionError("Missing task: " + taskId);
    }
}
