package dev.nathan.sbaagentic.dag;

import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.nathan.sbaagentic.workflow.CreateSessionLinkRequest;
import dev.nathan.sbaagentic.workflow.LinkType;
import dev.nathan.sbaagentic.workflow.internal.application.SessionLinkService;
import dev.nathan.sbaagentic.workflow.AnnotationKind;
import dev.nathan.sbaagentic.workflow.CreateAnnotationRequest;
import dev.nathan.sbaagentic.workflow.CreateSpecRequest;
import dev.nathan.sbaagentic.workflow.EnqueueTaskRequest;
import dev.nathan.sbaagentic.workflow.Task;
import dev.nathan.sbaagentic.workflow.TaskDomainException;
import dev.nathan.sbaagentic.workflow.TaskErrorCode;
import dev.nathan.sbaagentic.workflow.TaskEvent;
import dev.nathan.sbaagentic.workflow.TaskEventType;
import dev.nathan.sbaagentic.workflow.internal.adapter.out.sqlite.TaskRepository;
import dev.nathan.sbaagentic.workflow.internal.application.TaskService;
import dev.nathan.sbaagentic.workflow.TaskSpec;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite:target/dag-projection-test.db",
        "sba.local-ai.enabled=false",
        "sba.summary.backend=local",
        "sba.elasticsearch.enabled=false",
        "sba.ask.embedding-enabled=false"
})
@AutoConfigureMockMvc
class DagProjectionTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    TaskService taskService;

    @Autowired
    TaskRepository taskRepository;

    @Autowired
    SessionLinkService sessionLinkService;

    @Autowired
    DagService dagService;

    @BeforeEach
    void resetDatabase() {
        jdbcTemplate.update("DELETE FROM session_links");
        jdbcTemplate.update("DELETE FROM task_events");
        jdbcTemplate.update("DELETE FROM tasks");
        jdbcTemplate.update("DELETE FROM specs");
    }

    @Test
    void projectsTaskAndSessionEntryPointsWithOneHopExpansion() throws Exception {
        TaskSpec spec = taskService.createSpec(new CreateSpecRequest(
                "/repos/dag-projection",
                "DAG projection",
                "Project a bounded task and session graph.",
                Map.of("repo", "black-box"),
                "planner"));
        Task taskA = taskService.enqueueTask(new EnqueueTaskRequest(
                spec.id(), "Run worker", "codex", 10, "planner")).snapshot().task();
        Task taskB = taskService.enqueueTask(new EnqueueTaskRequest(
                spec.id(), "Review output", "codex", 5, "planner")).snapshot().task();
        taskService.createAnnotation(new CreateAnnotationRequest(
                taskA.id(),
                "codex",
                AnnotationKind.WORKER_SESSION.value(),
                "Worker session started.",
                Map.of("sessionId", "session-alpha")));
        sessionLinkService.createLink(new CreateSessionLinkRequest(
                "session-alpha",
                "session-beta",
                LinkType.SPAWNED.value(),
                taskA.id()));

        assertThat(taskRepository.listTasksBySpec(spec.id()))
                .extracting(Task::id)
                .containsExactly(taskA.id(), taskB.id());
        assertThat(taskRepository.eventsByType(TaskEventType.NOTE))
                .singleElement()
                .extracting(TaskEvent::taskId)
                .isEqualTo(taskA.id());

        DagResponse taskGraph = dagService.forTask(taskA.id());
        String specNodeId = "spec:" + spec.id();
        String taskANodeId = "task:" + taskA.id();
        String taskBNodeId = "task:" + taskB.id();
        String alphaNodeId = "session:session-alpha";
        String betaNodeId = "session:session-beta";
        assertThat(taskGraph.nodes())
                .extracting(DagNode::id)
                .containsExactlyInAnyOrder(
                        specNodeId, taskANodeId, taskBNodeId, alphaNodeId, betaNodeId);
        assertThat(taskGraph.nodes())
                .filteredOn(node -> node.id().equals(specNodeId))
                .singleElement()
                .satisfies(node -> {
                    assertThat(node.type()).isEqualTo("spec");
                    assertThat(node.label()).isEqualTo(spec.title());
                    assertThat(node.status()).isEqualTo("active");
                    assertThat(node.ref()).isEqualTo(spec.id());
                });
        assertThat(taskGraph.nodes())
                .filteredOn(node -> node.id().equals(taskBNodeId))
                .singleElement()
                .satisfies(node -> {
                    assertThat(node.type()).isEqualTo("task");
                    assertThat(node.label()).isEqualTo(taskB.title());
                    assertThat(node.status()).isEqualTo("open");
                    assertThat(node.ref()).isEqualTo(taskB.id());
                });
        assertThat(taskGraph.edges()).containsExactlyInAnyOrder(
                new DagEdge(specNodeId, taskANodeId, "has_task"),
                new DagEdge(specNodeId, taskBNodeId, "has_task"),
                new DagEdge(taskANodeId, alphaNodeId, "worker_session"),
                new DagEdge(alphaNodeId, betaNodeId, "spawned"));
        assertThat(taskGraph.edges()).noneMatch(edge ->
                (edge.from().equals(taskBNodeId) && edge.to().startsWith("session:"))
                        || (edge.to().equals(taskBNodeId) && edge.from().startsWith("session:")));

        DagResponse alphaGraph = dagService.forSession("session-alpha");
        assertThat(alphaGraph.nodes())
                .extracting(DagNode::id)
                .containsExactlyInAnyOrderElementsOf(
                        taskGraph.nodes().stream().map(DagNode::id).toList());
        assertThat(alphaGraph.edges())
                .containsExactlyInAnyOrderElementsOf(taskGraph.edges());

        DagResponse betaGraph = dagService.forSession("session-beta");
        assertThat(betaGraph.nodes())
                .extracting(DagNode::id)
                .containsExactlyInAnyOrder(alphaNodeId, betaNodeId);
        assertThat(betaGraph.nodes()).noneMatch(node ->
                node.type().equals("spec") || node.type().equals("task"));
        assertThat(betaGraph.edges())
                .containsExactly(new DagEdge(alphaNodeId, betaNodeId, "spawned"));

        String orphanSessionId = "session-nonexistent-orphan";
        DagResponse orphanGraph = dagService.forSession(orphanSessionId);
        assertThat(orphanGraph.nodes()).containsExactly(new DagNode(
                "session:" + orphanSessionId,
                "session",
                orphanSessionId,
                null,
                orphanSessionId));
        assertThat(orphanGraph.edges()).isEmpty();

        String unknownTaskId = UUID.randomUUID().toString();
        assertThatThrownBy(() -> dagService.forTask(unknownTaskId))
                .isInstanceOfSatisfying(TaskDomainException.class, ex ->
                        assertThat(ex.code()).isEqualTo(TaskErrorCode.TASK_NOT_FOUND));

        MvcResult taskDagResult = mockMvc.perform(get("/api/tasks/{taskId}/dag", taskA.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodes.length()").value(5))
                .andExpect(jsonPath("$.edges.length()").value(4))
                .andReturn();
        DagResponse taskApiGraph = objectMapper.readValue(
                taskDagResult.getResponse().getContentAsString(), DagResponse.class);
        assertThat(taskApiGraph.nodes()).containsExactlyInAnyOrderElementsOf(taskGraph.nodes());
        assertThat(taskApiGraph.edges()).containsExactlyInAnyOrderElementsOf(taskGraph.edges());

        MvcResult betaDagResult = mockMvc.perform(get("/api/dag")
                        .param("sessionId", "session-beta"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodes.length()").value(2))
                .andExpect(jsonPath("$.edges.length()").value(1))
                .andReturn();
        DagResponse betaApiGraph = objectMapper.readValue(
                betaDagResult.getResponse().getContentAsString(), DagResponse.class);
        assertThat(betaApiGraph.nodes()).containsExactlyInAnyOrderElementsOf(betaGraph.nodes());
        assertThat(betaApiGraph.edges()).containsExactlyInAnyOrderElementsOf(betaGraph.edges());

        mockMvc.perform(get("/api/tasks/{taskId}/dag", unknownTaskId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.type").value("task_not_found"));
    }
}
