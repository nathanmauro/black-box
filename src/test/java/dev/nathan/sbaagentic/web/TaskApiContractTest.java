package dev.nathan.sbaagentic.web;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.nathan.sbaagentic.mcp.AgenticTools;
import dev.nathan.sbaagentic.workflow.TaskChange;
import dev.nathan.sbaagentic.workflow.TaskDomainException;
import dev.nathan.sbaagentic.workflow.TaskErrorCode;
import dev.nathan.sbaagentic.workflow.TaskEvent;
import dev.nathan.sbaagentic.workflow.internal.adapter.out.sqlite.TaskRepository;
import dev.nathan.sbaagentic.workflow.TaskSnapshot;
import dev.nathan.sbaagentic.workflow.TaskSpec;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.ai.mcp.McpToolUtils;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite:target/task-api-contract-test.db",
        "sba.local-ai.enabled=false",
        "sba.summary.backend=local",
        "sba.elasticsearch.enabled=false",
        "sba.ask.embedding-enabled=false"
})
@AutoConfigureMockMvc
class TaskApiContractTest {

    private static final String PROJECT = "/repos/task-api-contract";
    private static final Set<String> EXISTING_TOOLS = Set.of(
            "recentSessions",
            "searchSessions",
            "recallContext",
            "captureDecision",
            "captureHandoff",
            "captureObservation",
            "localModelStatus");
    private static final Set<String> TASK_TOOLS = Set.of(
            "createSpec",
            "enqueueTask",
            "claimNextTask",
            "updateTaskStatus",
            "completeTask",
            "listTasks",
            "getSpec");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    TaskRepository taskRepository;

    @Autowired
    AgenticTools tools;

    @Autowired
    @Qualifier("agenticToolCallbacks")
    ToolCallbackProvider toolCallbackProvider;

    @BeforeEach
    void resetDatabase() {
        jdbcTemplate.update("DELETE FROM task_events");
        jdbcTemplate.update("DELETE FROM tasks");
        jdbcTemplate.update("DELETE FROM specs");
        jdbcTemplate.update("DELETE FROM agent_events");
        jdbcTemplate.update("DELETE FROM agent_sessions");
    }

    @Test
    void isolatedRestTranscriptCreatesEnqueuesClaimsCompletesAndRecalls() throws Exception {
        MvcResult create = mockMvc.perform(post("/api/specs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "projectKey": "%s",
                                  "title": "REST queue contract",
                                  "body": "Frozen specification body available without a checkout.",
                                  "specRef": {"repo":"black-box","path":"docs/spec.md","sha":"abc123"},
                                  "actor": "planner"
                                }
                                """.formatted(PROJECT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("active"))
                .andExpect(jsonPath("$.body").value("Frozen specification body available without a checkout."))
                .andReturn();
        JsonNode createdSpec = json(create);

        MvcResult enqueue = mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "specId": "%s",
                                  "title": "Expose task contracts",
                                  "lane": "codex",
                                  "priority": 9,
                                  "actor": "planner"
                                }
                                """.formatted(createdSpec.get("id").asText())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.snapshot.task.status").value("open"))
                .andExpect(jsonPath("$.snapshot.spec.body")
                        .value("Frozen specification body available without a checkout."))
                .andExpect(jsonPath("$.event.type").value("task.created"))
                .andReturn();
        JsonNode enqueued = json(enqueue);

        MvcResult claim = mockMvc.perform(post("/api/tasks/claim")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"lane":"codex","agent":"worker-1"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.snapshot.task.status").value("in_progress"))
                .andExpect(jsonPath("$.snapshot.task.claimedBy").value("worker-1"))
                .andExpect(jsonPath("$.snapshot.spec.body")
                        .value("Frozen specification body available without a checkout."))
                .andExpect(jsonPath("$.event.type").value("task.claimed"))
                .andReturn();
        JsonNode claimed = json(claim);

        MvcResult complete = mockMvc.perform(post(
                                "/api/tasks/{taskId}/complete",
                                claimed.at("/snapshot/task/id").asText())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "actor":"worker-1",
                                  "source":"codex",
                                  "clientSessionId":"rest-transcript-session",
                                  "summary":"REST and MCP task contracts are exposed.",
                                  "openLoops":["Board client remains"],
                                  "nextAction":"Build the typed Board client."
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.snapshot.task.status").value("done"))
                .andExpect(jsonPath("$.snapshot.task.resultHandoffId").isNotEmpty())
                .andExpect(jsonPath("$.event.type").value("task.completed"))
                .andReturn();
        JsonNode completed = json(complete);
        String handoffId = completed.at("/snapshot/task/resultHandoffId").asText();

        MvcResult recall = mockMvc.perform(get("/api/recall")
                        .param("scope", handoffId)
                        .param("kinds", "handoff"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].eventId").value(handoffId))
                .andExpect(jsonPath("$.items[0].clientSessionId").value("rest-transcript-session"))
                .andReturn();
        assertThat(tools.recallContext(handoffId, 168, List.of("handoff")).items())
                .singleElement()
                .extracting(item -> item.eventId())
                .isEqualTo(handoffId);
        JsonNode mcpRecall = json(callback("recallContext").call("""
                {"repoOrTopic":"%s","withinHours":168,"kinds":["handoff"]}
                """.formatted(handoffId)));
        assertThat(mcpRecall.at("/items/0/eventId").asText()).isEqualTo(handoffId);

        mockMvc.perform(post("/api/tasks/claim")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lane\":\"codex\",\"agent\":\"worker-2\"}"))
                .andExpect(status().isNoContent())
                .andExpect(jsonPath("$").doesNotExist());

        System.out.println("REST_TRANSCRIPT POST /api/specs 200 " + create.getResponse().getContentAsString());
        System.out.println("REST_TRANSCRIPT POST /api/tasks 200 " + enqueue.getResponse().getContentAsString());
        System.out.println("REST_TRANSCRIPT POST /api/tasks/claim 200 " + claim.getResponse().getContentAsString());
        System.out.println("REST_TRANSCRIPT POST /api/tasks/{id}/complete 200 "
                + complete.getResponse().getContentAsString());
        System.out.println("REST_TRANSCRIPT GET /api/recall 200 " + recall.getResponse().getContentAsString());
        System.out.println("MCP_TRANSCRIPT recallContext " + mcpRecall);

        assertThat(enqueued.at("/snapshot/task/id").asText())
                .isEqualTo(claimed.at("/snapshot/task/id").asText());
    }

    @Test
    void restAndMcpExposeEquivalentFieldsAndCanonicalState() throws Exception {
        JsonNode restSpec = json(mockMvc.perform(post("/api/specs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(specJson(PROJECT, "REST spec", "REST frozen body")))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(objectMapper.<JsonNode>valueToTree(tools.getSpec(restSpec.get("id").asText())))
                .isEqualTo(restSpec);

        TaskSpec mcpSpec = tools.createSpec(
                PROJECT + "/mcp",
                "MCP spec",
                "MCP frozen body",
                Map.of("repo", "black-box"),
                "planner");
        assertThat(json(mockMvc.perform(get("/api/specs/{id}", mcpSpec.id()))
                        .andExpect(status().isOk())
                        .andReturn()))
                .isEqualTo(objectMapper.valueToTree(mcpSpec));

        JsonNode restCreated = json(mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(enqueueJson(restSpec.get("id").asText(), "parity task", "codex", 5)))
                .andExpect(status().isOk())
                .andReturn());
        List<TaskSnapshot> mcpOpen = tools.listTasks(PROJECT, "codex", "open", 50);
        assertThat(mcpOpen).singleElement();
        assertThat(objectMapper.<JsonNode>valueToTree(mcpOpen.getFirst()))
                .isEqualTo(restCreated.get("snapshot"));

        TaskChange mcpClaimed = tools.claimNextTask("codex", "worker");
        assertThat(mcpClaimed.snapshot().spec().body()).isEqualTo("REST frozen body");
        JsonNode restInProgress = json(mockMvc.perform(get("/api/tasks")
                        .param("projectKey", PROJECT)
                        .param("lane", "codex")
                        .param("status", "in_progress"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].spec.body").value("REST frozen body"))
                .andReturn());
        assertThat(restInProgress.get(0))
                .isEqualTo(objectMapper.valueToTree(mcpClaimed.snapshot()));

        JsonNode restBlocked = json(mockMvc.perform(patch(
                                "/api/tasks/{id}", mcpClaimed.snapshot().task().id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actor\":\"worker\",\"status\":\"blocked\",\"blockedReason\":\"waiting\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.event.type").value("task.blocked"))
                .andReturn());
        assertThat(objectMapper.<JsonNode>valueToTree(
                tools.listTasks(PROJECT, "codex", "blocked", 50).getFirst()))
                .isEqualTo(restBlocked.get("snapshot"));

        TaskChange mcpReset = tools.updateTaskStatus(
                mcpClaimed.snapshot().task().id(), "operator", "open", null);
        JsonNode restOpen = json(mockMvc.perform(get("/api/tasks")
                        .param("projectKey", PROJECT)
                        .param("status", "open"))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(restOpen.get(0)).isEqualTo(objectMapper.valueToTree(mcpReset.snapshot()));

        JsonNode restClaimed = json(mockMvc.perform(post("/api/tasks/claim")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lane\":\"codex\",\"agent\":\"worker\"}"))
                .andExpect(status().isOk())
                .andReturn());
        TaskChange mcpCompleted = tools.completeTask(
                restClaimed.at("/snapshot/task/id").asText(),
                "worker",
                "codex",
                "mcp-parity-session",
                "Completed through MCP.",
                List.of("none"),
                "Inspect the linked Handoff.");
        JsonNode restDone = json(mockMvc.perform(get("/api/tasks")
                        .param("projectKey", PROJECT)
                        .param("status", "done"))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(restDone.get(0)).isEqualTo(objectMapper.valueToTree(mcpCompleted.snapshot()));
        assertThat(mcpCompleted.snapshot().task().resultHandoffId()).isNotBlank();

        TaskChange mcpCreated = tools.enqueueTask(
                mcpSpec.id(), "MCP-created task", "claude", 2, "planner");
        JsonNode restMcpCreated = json(mockMvc.perform(get("/api/tasks")
                        .param("projectKey", PROJECT + "/mcp")
                        .param("lane", "claude"))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(restMcpCreated.get(0)).isEqualTo(objectMapper.valueToTree(mcpCreated.snapshot()));
        assertThat(tools.claimNextTask("empty-lane", "nobody")).isNull();
    }

    @Test
    void taskAnnotationsAreAppendOnlyEventsInEveryTaskStatus() throws Exception {
        TaskSpec spec = tools.createSpec(PROJECT, "annotations", "frozen body", null, "planner");
        TaskChange created = tools.enqueueTask(spec.id(), "annotated task", "codex", 5, "planner");
        String taskId = created.snapshot().task().id();

        MvcResult annotationResult = mockMvc.perform(post("/api/tasks/{taskId}/annotations", taskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "actor":"worker",
                                  "kind":"progress",
                                  "text":"Implemented the persistence slice.",
                                  "dataJson":{"percent":60,"phase":"persistence"}
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.taskId").value(taskId))
                .andExpect(jsonPath("$.kind").value("progress"))
                .andExpect(jsonPath("$.actor").value("worker"))
                .andExpect(jsonPath("$.text").value("Implemented the persistence slice."))
                .andExpect(jsonPath("$.observedAt").isString())
                .andExpect(jsonPath("$.dataJson.percent").value(60))
                .andExpect(jsonPath("$.dataJson.phase").value("persistence"))
                .andReturn();
        assertTimestampString(json(annotationResult), "/observedAt");

        mockMvc.perform(get("/api/tasks/{taskId}/events", taskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("task.created"))
                .andExpect(jsonPath("$[1].type").value("task.note"))
                .andExpect(jsonPath("$[1].detail.kind").value("progress"))
                .andExpect(jsonPath("$[1].detail.text")
                        .value("Implemented the persistence slice."))
                .andExpect(jsonPath("$[1].detail.dataJson.percent").value(60));

        tools.claimNextTask("codex", "worker");
        tools.completeTask(
                taskId,
                "worker",
                "codex",
                "annotation-done-session",
                "Completed before the final annotation.",
                List.of(),
                "Record the final engine note.");

        mockMvc.perform(post("/api/tasks/{taskId}/annotations", taskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "actor":"engine",
                                  "kind":"engine",
                                  "text":"Task is already done."
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value(taskId))
                .andExpect(jsonPath("$.kind").value("engine"))
                .andExpect(jsonPath("$.text").value("Task is already done."));
    }

    @Test
    void annotationEndpointsReturnStableValidationAndNotFoundErrors() throws Exception {
        TaskSpec spec = tools.createSpec(PROJECT, "annotation errors", "body", null, "planner");
        String taskId = tools.enqueueTask(spec.id(), "annotation errors", "codex", 1, "planner")
                .snapshot().task().id();

        mockMvc.perform(post("/api/tasks/{taskId}/annotations", taskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"actor":"worker","kind":"bogus","text":"Unknown kind."}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.type").value("validation_failed"));

        mockMvc.perform(post("/api/tasks/{taskId}/annotations", taskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"actor":" ","kind":"note","text":"Missing actor."}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.type").value("validation_failed"));

        String missingTask = UUID.randomUUID().toString();
        mockMvc.perform(post("/api/tasks/{taskId}/annotations", missingTask)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"actor":"worker","kind":"note","text":"Missing task."}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.type").value("task_not_found"));
        mockMvc.perform(get("/api/tasks/{taskId}/events", missingTask))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.type").value("task_not_found"));

        mockMvc.perform(post("/api/tasks/not-a-uuid/annotations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"actor":"worker","kind":"note","text":"Malformed task id."}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.type").value("validation_failed"));
        mockMvc.perform(get("/api/tasks/not-a-uuid/events"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.type").value("validation_failed"));
    }

    @Test
    void actualTaskCallbacksUseRestJsonForEverySuccessfulOperation() throws Exception {
        JsonNode created = json(callback("createSpec").call("""
                {
                  "projectKey":"%s",
                  "title":"Callback JSON parity",
                  "body":"Frozen callback specification.",
                  "specRef":{"repo":"black-box"},
                  "actor":"planner"
                }
                """.formatted(PROJECT)));
        String specId = created.get("id").asText();
        JsonNode restSpec = json(mockMvc.perform(get("/api/specs/{id}", specId))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(created).isEqualTo(restSpec);
        assertSpecTimestampStrings(created);

        JsonNode fetched = json(callback("getSpec").call("{\"specId\":\"%s\"}".formatted(specId)));
        assertThat(fetched).isEqualTo(restSpec);
        assertSpecTimestampStrings(fetched);

        JsonNode enqueued = json(callback("enqueueTask").call("""
                {
                  "specId":"%s",
                  "title":"Prove callback JSON parity",
                  "lane":"codex",
                  "priority":7,
                  "actor":"planner"
                }
                """.formatted(specId)));
        String taskId = enqueued.at("/snapshot/task/id").asText();
        assertTaskChangeMatchesRest(enqueued);

        JsonNode listed = json(callback("listTasks").call("""
                {"projectKey":"%s","lane":"codex","status":"open"}
                """.formatted(PROJECT)));
        JsonNode restList = json(mockMvc.perform(get("/api/tasks")
                        .param("projectKey", PROJECT)
                        .param("lane", "codex")
                        .param("status", "open"))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(listed).isEqualTo(restList);
        assertSnapshotTimestampStrings(listed.get(0));

        JsonNode claimed = json(callback("claimNextTask").call(
                "{\"lane\":\"codex\",\"agent\":\"worker\"}"));
        assertThat(claimed.at("/snapshot/task/id").asText()).isEqualTo(taskId);
        assertTaskChangeMatchesRest(claimed);

        JsonNode blocked = json(callback("updateTaskStatus").call("""
                {"taskId":"%s","actor":"worker","status":"blocked","blockedReason":"waiting"}
                """.formatted(taskId)));
        assertTaskChangeMatchesRest(blocked);

        JsonNode reopened = json(callback("updateTaskStatus").call("""
                {"taskId":"%s","actor":"operator","status":"open"}
                """.formatted(taskId)));
        assertTaskChangeMatchesRest(reopened);
        json(callback("claimNextTask").call("{\"lane\":\"codex\",\"agent\":\"worker\"}"));

        JsonNode completed = json(callback("completeTask").call("""
                {
                  "taskId":"%s",
                  "actor":"worker",
                  "source":"codex",
                  "clientSessionId":"callback-json-session",
                  "summary":"All task callbacks use REST JSON.",
                  "openLoops":[],
                  "nextAction":"Proceed to the Board client."
                }
                """.formatted(taskId)));
        assertTaskChangeMatchesRest(completed);
        assertThat(completed.at("/snapshot/task/resultHandoffId").asText()).isNotBlank();

        assertThat(callback("claimNextTask").call("{\"lane\":\"codex\",\"agent\":\"nobody\"}"))
                .isEqualTo("null");
        System.out.println("MCP_SUCCESS_TIMESTAMP_SAMPLE=" + completed.at("/event/observedAt").asText());
        System.out.println("MCP_SUCCESS_COMPLETED=" + completed);
    }

    @Test
    void listFiltersAreExplicitAndLimitsAreBoundedIdentically() throws Exception {
        TaskSpec bulkSpec = tools.createSpec(PROJECT, "bulk", "body", null, "planner");
        for (int i = 0; i < 251; i++) {
            tools.enqueueTask(bulkSpec.id(), "bulk-" + i, "codex", 0, "planner");
        }
        TaskSpec otherSpec = tools.createSpec(PROJECT + "/other", "other", "body", null, "planner");
        TaskChange other = tools.enqueueTask(otherSpec.id(), "other", "claude", 10_000, "planner");

        JsonNode restBounded = json(mockMvc.perform(get("/api/tasks")
                        .param("projectKey", PROJECT)
                        .param("lane", "codex")
                        .param("status", "open")
                        .param("limit", "999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(250))
                .andReturn());
        assertThat(restBounded)
                .isEqualTo(objectMapper.valueToTree(tools.listTasks(PROJECT, "codex", "open", 999)));

        JsonNode unfiltered = json(mockMvc.perform(get("/api/tasks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(100))
                .andReturn());
        assertThat(ids(unfiltered)).contains(other.snapshot().task().id());
        ToolCallback listCallback = callback("listTasks");
        JsonNode callbackDefault = json(listCallback.call("{}"));
        assertThat(callbackDefault).hasSize(100);
        assertThat(ids(callbackDefault)).isEqualTo(ids(unfiltered));

        JsonNode filtered = json(mockMvc.perform(get("/api/tasks")
                        .param("projectKey", PROJECT)
                        .param("limit", "250"))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(ids(filtered)).doesNotContain(other.snapshot().task().id());

        mockMvc.perform(get("/api/tasks").param("limit", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
        assertThat(tools.listTasks(null, null, null, 0)).hasSize(1);
    }

    @Test
    void malformedAndDomainFailuresReturnStableErrorsWithoutPartialWrites() throws Exception {
        mockMvc.perform(post("/api/specs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(specJson(PROJECT, "bad", " ")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.type").value("validation_failed"));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM specs", Integer.class)).isZero();

        mockMvc.perform(get("/api/specs/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.type").value("validation_failed"))
                .andExpect(jsonPath("$.error.message").value("Spec id must be a UUID"));

        mockMvc.perform(get("/api/specs/1-1-1-1-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.type").value("validation_failed"))
                .andExpect(jsonPath("$.error.message").value("Spec id must be a UUID"));

        String missingSpec = UUID.randomUUID().toString();
        mockMvc.perform(get("/api/specs/{id}", missingSpec))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.type").value("spec_not_found"));

        mockMvc.perform(get("/api/tasks").param("status", "running"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.type").value("validation_failed"));
        mockMvc.perform(get("/api/tasks").param("limit", "not-an-int"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.type").value("invalid_argument"));

        mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(enqueueJson(missingSpec, "missing spec", "codex", 1)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.type").value("spec_not_found"));

        TaskSpec spec = tools.createSpec(PROJECT, "errors", "body", null, "planner");
        mockMvc.perform(get("/api/specs/{id}", spec.id().toUpperCase(Locale.ROOT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(spec.id()));
        TaskChange created = tools.enqueueTask(spec.id(), "error task", "codex", 1, "planner");
        String taskId = created.snapshot().task().id();
        int eventsBefore = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM task_events WHERE task_id = ?", Integer.class, taskId);

        MvcResult invalidTransition = mockMvc.perform(patch("/api/tasks/{id}", taskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actor\":\"worker\",\"status\":\"blocked\",\"blockedReason\":\"waiting\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.type").value("invalid_transition"))
                .andReturn();
        assertThat(invalidTransition.getResponse().getContentAsString())
                .doesNotContain("TaskDomainException", "java.", "\tat ");
        TaskDomainException mcpTransition = catchThrowableOfType(
                () -> tools.updateTaskStatus(taskId, "worker", "blocked", "waiting"),
                TaskDomainException.class);
        assertThat(mcpTransition.code()).isEqualTo(TaskErrorCode.INVALID_TRANSITION);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM task_events WHERE task_id = ?", Integer.class, taskId))
                .isEqualTo(eventsBefore);

        mockMvc.perform(patch("/api/tasks/{id}", taskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actor\":\"worker\",\"status\":\"not-real\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.type").value("validation_failed"));
        mockMvc.perform(post("/api/tasks/claim")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lane\":\"codex\",\"agent\":\"owner\"}"))
                .andExpect(status().isOk());

        MvcResult ownership = mockMvc.perform(post("/api/tasks/{id}/complete", taskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "actor":"intruder",
                                  "source":"codex",
                                  "clientSessionId":"intruder-session",
                                  "summary":"Should not complete.",
                                  "nextAction":"None."
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.type").value("claimant_mismatch"))
                .andReturn();
        assertThat(ownership.getResponse().getContentAsString())
                .doesNotContain("TaskDomainException", "java.", "\tat ");
        TaskDomainException mcpOwnership = catchThrowableOfType(
                () -> tools.completeTask(
                        taskId, "intruder", "codex", "intruder-session", "No", null, "No"),
                TaskDomainException.class);
        assertThat(mcpOwnership.code()).isEqualTo(TaskErrorCode.CLAIMANT_MISMATCH);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM agent_sessions", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM tasks WHERE id = ?", String.class, taskId)).isEqualTo("in_progress");

        String missingTask = UUID.randomUUID().toString();
        mockMvc.perform(patch("/api/tasks/{id}", missingTask)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actor\":\"operator\",\"status\":\"cancelled\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.type").value("task_not_found"));
        mockMvc.perform(post("/api/specs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.type").value("malformed_json"));

        TaskDomainException malformedMcpId = catchThrowableOfType(
                () -> tools.getSpec("not-a-uuid"), TaskDomainException.class);
        assertThat(malformedMcpId.code()).isEqualTo(TaskErrorCode.VALIDATION_FAILED);
    }

    @Test
    void mcpCallbacksExposeStructuredTaskErrorsAndPreserveErrorTransport() throws Exception {
        JsonNode abbreviated = callbackError("getSpec", "{\"specId\":\"1-1-1-1-1\"}");
        assertTaskError(abbreviated, "validation_failed", "VALIDATION_FAILED");

        String missingSpec = UUID.randomUUID().toString();
        JsonNode notFound = callbackError("getSpec", "{\"specId\":\"%s\"}".formatted(missingSpec));
        assertTaskError(notFound, "spec_not_found", "SPEC_NOT_FOUND");

        TaskSpec spec = tools.createSpec(PROJECT, "callback errors", "frozen", null, "planner");
        JsonNode uppercaseSpec = json(callback("getSpec").call(
                "{\"specId\":\"%s\"}".formatted(spec.id().toUpperCase(Locale.ROOT))));
        assertThat(uppercaseSpec.get("id").asText()).isEqualTo(spec.id());

        TaskChange created = tools.enqueueTask(spec.id(), "callback task", "codex", 1, "planner");
        String taskId = created.snapshot().task().id();
        String invalidInput = """
                {"taskId":"%s","actor":"worker","status":"blocked","blockedReason":"waiting"}
                """.formatted(taskId);
        JsonNode transition = callbackError("updateTaskStatus", invalidInput);
        assertTaskError(transition, "invalid_transition", "INVALID_TRANSITION");

        McpSchema.CallToolResult transportError = McpToolUtils
                .toSyncToolSpecification(callback("updateTaskStatus"))
                .call()
                .apply(org.mockito.Mockito.mock(McpSyncServerExchange.class), Map.of(
                        "taskId", taskId,
                        "actor", "worker",
                        "status", "blocked",
                        "blockedReason", "waiting"));
        assertThat(transportError.isError()).isTrue();
        McpSchema.TextContent errorContent = (McpSchema.TextContent) transportError.content().getFirst();
        assertTaskError(json(errorContent.text()), "invalid_transition", "INVALID_TRANSITION");

        tools.claimNextTask("codex", "owner");
        String ownershipInput = """
                {
                  "taskId":"%s",
                  "actor":"intruder",
                  "source":"codex",
                  "clientSessionId":"intruder-session",
                  "summary":"Should fail.",
                  "nextAction":"None."
                }
                """.formatted(taskId);
        JsonNode ownership = callbackError("completeTask", ownershipInput);
        assertTaskError(ownership, "claimant_mismatch", "CLAIMANT_MISMATCH");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM agent_sessions", Integer.class)).isZero();

        System.out.println("MCP_STRUCTURED_ERROR=" + abbreviated);
        System.out.println("MCP_IS_ERROR_RESULT=" + errorContent.text());
    }

    @Test
    void existingToolsRemainSourceCompatibleAndFourteenToolsAreRegistered() throws Exception {
        Set<String> registered = Arrays.stream(toolCallbackProvider.getToolCallbacks())
                .map(callback -> callback.getToolDefinition().name())
                .collect(Collectors.toSet());
        assertThat(registered).hasSize(14).containsAll(EXISTING_TOOLS).containsAll(TASK_TOOLS);

        assertThat(AgenticTools.class.getConstructor(
                dev.nathan.sbaagentic.event.EventRepository.class,
                dev.nathan.sbaagentic.context.ContextService.class,
                dev.nathan.sbaagentic.search.SearchService.class,
                dev.nathan.sbaagentic.ai.LocalAiClient.class)).isNotNull();

        Method recent = AgenticTools.class.getMethod("recentSessions", int.class);
        Method search = AgenticTools.class.getMethod("searchSessions", String.class, int.class);
        Method recall = AgenticTools.class.getMethod(
                "recallContext", String.class, int.class, List.class);
        Method decision = AgenticTools.class.getMethod(
                "captureDecision",
                String.class,
                String.class,
                String.class,
                String.class,
                String.class,
                List.class,
                double.class,
                List.class);
        Method handoff = AgenticTools.class.getMethod(
                "captureHandoff",
                String.class,
                String.class,
                String.class,
                String.class,
                String.class,
                List.class,
                String.class);
        Method observation = AgenticTools.class.getMethod(
                "captureObservation", String.class, String.class, String.class, String.class);
        Method modelStatus = AgenticTools.class.getMethod("localModelStatus");
        assertThat(List.of(recent, search, recall, decision, handoff, observation, modelStatus))
                .allSatisfy(method -> assertThat(method.getAnnotation(org.springframework.ai.tool.annotation.Tool.class))
                        .isNotNull());

        ToolCallback claim = Arrays.stream(toolCallbackProvider.getToolCallbacks())
                .filter(callback -> callback.getToolDefinition().name().equals("claimNextTask"))
                .findFirst()
                .orElseThrow();
        String emptyClaim = claim.call("{\"lane\":\"empty\",\"agent\":\"nobody\"}");
        assertThat(emptyClaim).isEqualTo("null");
        System.out.println("MCP_EMPTY_CLAIM_RESULT=" + emptyClaim);

        ToolCallback list = Arrays.stream(toolCallbackProvider.getToolCallbacks())
                .filter(callback -> callback.getToolDefinition().name().equals("listTasks"))
                .findFirst()
                .orElseThrow();
        JsonNode listSchema = objectMapper.readTree(list.getToolDefinition().inputSchema());
        assertThat(listSchema.path("required").isMissingNode() || listSchema.path("required").isEmpty()).isTrue();
        assertThat(list.call("{}")).isEqualTo("[]");
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode json(String value) throws Exception {
        return objectMapper.readTree(value);
    }

    private ToolCallback callback(String name) {
        return Arrays.stream(toolCallbackProvider.getToolCallbacks())
                .filter(candidate -> candidate.getToolDefinition().name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private JsonNode callbackError(String toolName, String input) throws Exception {
        ToolExecutionException failure = catchThrowableOfType(
                () -> callback(toolName).call(input), ToolExecutionException.class);
        assertThat(failure).isNotNull();
        assertThat(failure.getMessage()).doesNotContain("TaskDomainException", "java.", "\tat ");
        return json(failure.getMessage());
    }

    private void assertTaskChangeMatchesRest(JsonNode change) throws Exception {
        String taskId = change.at("/snapshot/task/id").asText();
        JsonNode restTasks = json(mockMvc.perform(get("/api/tasks")
                        .param("projectKey", PROJECT)
                        .param("limit", "250"))
                .andExpect(status().isOk())
                .andReturn());
        JsonNode restSnapshot = null;
        for (JsonNode candidate : restTasks) {
            if (taskId.equals(candidate.at("/task/id").asText())) {
                restSnapshot = candidate;
                break;
            }
        }
        assertThat(restSnapshot).isNotNull().isEqualTo(change.get("snapshot"));

        TaskEvent latestEvent = taskRepository.eventsForTask(taskId).getLast();
        assertThat(change.get("event")).isEqualTo(objectMapper.<JsonNode>valueToTree(latestEvent));
        assertSnapshotTimestampStrings(change.get("snapshot"));
        assertTimestampString(change, "/event/observedAt");
    }

    private static void assertSnapshotTimestampStrings(JsonNode snapshot) {
        assertTimestampString(snapshot, "/task/createdAt");
        assertTimestampString(snapshot, "/task/updatedAt");
        assertTimestampString(snapshot, "/spec/createdAt");
        assertTimestampString(snapshot, "/spec/updatedAt");
    }

    private static void assertSpecTimestampStrings(JsonNode spec) {
        assertTimestampString(spec, "/createdAt");
        assertTimestampString(spec, "/updatedAt");
    }

    private static void assertTimestampString(JsonNode value, String pointer) {
        JsonNode timestamp = value.at(pointer);
        assertThat(timestamp.isTextual()).as(pointer + " is an ISO-8601 string").isTrue();
        assertThat(Instant.parse(timestamp.asText())).isNotNull();
    }

    private static void assertTaskError(JsonNode payload, String type, String code) {
        assertThat(payload.at("/error/type").asText()).isEqualTo(type);
        assertThat(payload.at("/error/code").asText()).isEqualTo(code);
        assertThat(payload.at("/error/message").asText()).isNotBlank();
    }

    private static Set<String> ids(JsonNode array) {
        return array.findValuesAsText("id").stream().collect(Collectors.toSet());
    }

    private static String specJson(String projectKey, String title, String body) {
        return """
                {
                  "projectKey":"%s",
                  "title":"%s",
                  "body":"%s",
                  "actor":"planner"
                }
                """.formatted(projectKey, title, body);
    }

    private static String enqueueJson(String specId, String title, String lane, int priority) {
        return """
                {
                  "specId":"%s",
                  "title":"%s",
                  "lane":"%s",
                  "priority":%d,
                  "actor":"planner"
                }
                """.formatted(specId, title, lane, priority);
    }
}
