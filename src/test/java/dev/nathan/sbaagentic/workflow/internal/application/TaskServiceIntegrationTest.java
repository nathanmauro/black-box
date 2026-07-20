package dev.nathan.sbaagentic.workflow.internal.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.nathan.sbaagentic.context.ContextService;
import dev.nathan.sbaagentic.context.RecallResult;
import dev.nathan.sbaagentic.context.RecalledItem;
import dev.nathan.sbaagentic.workflow.ClaimTaskRequest;
import dev.nathan.sbaagentic.workflow.CompleteTaskRequest;
import dev.nathan.sbaagentic.workflow.CreateAnnotationRequest;
import dev.nathan.sbaagentic.workflow.CreateSpecRequest;
import dev.nathan.sbaagentic.workflow.EnqueueTaskRequest;
import dev.nathan.sbaagentic.workflow.Task;
import dev.nathan.sbaagentic.workflow.TaskChange;
import dev.nathan.sbaagentic.workflow.TaskDomainException;
import dev.nathan.sbaagentic.workflow.TaskErrorCode;
import dev.nathan.sbaagentic.workflow.TaskEvent;
import dev.nathan.sbaagentic.workflow.TaskEventType;
import dev.nathan.sbaagentic.workflow.TaskQuery;
import dev.nathan.sbaagentic.workflow.TaskSpec;
import dev.nathan.sbaagentic.workflow.TaskStatus;
import dev.nathan.sbaagentic.workflow.UpdateTaskStatusRequest;
import dev.nathan.sbaagentic.workflow.WorkflowPublication;
import dev.nathan.sbaagentic.workflow.WorkflowTaskChanged;
import dev.nathan.sbaagentic.workflow.internal.adapter.out.sqlite.TaskRepository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite:target/task-service-test.db",
        "sba.local-ai.enabled=false",
        "sba.summary.backend=local",
        "sba.elasticsearch.enabled=false"
})
class TaskServiceIntegrationTest {

    private static final String PROJECT = "/repos/task-service-test";

    @Autowired
    TaskService service;

    @Autowired
    TaskRepository repository;

    @Autowired
    ContextService contextService;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    ObjectMapper objectMapper;

    @MockitoBean
    WorkflowPublication publication;

    @BeforeEach
    void resetDatabase() {
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS fail_handoff_event_insert");
        jdbcTemplate.update("DELETE FROM task_events");
        jdbcTemplate.update("DELETE FROM tasks");
        jdbcTemplate.update("DELETE FROM specs");
        jdbcTemplate.update("DELETE FROM agent_events");
        jdbcTemplate.update("DELETE FROM agent_sessions");
        clearInvocations(publication);
    }

    @AfterEach
    void removeFailureTrigger() {
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS fail_handoff_event_insert");
    }

    @Test
    void allAllowedLifecycleTransitionsPreserveInvariantsAndPublishOnce() {
        TaskSpec spec = createSpec();
        TaskChange firstCreated = service.enqueueTask(new EnqueueTaskRequest(
                spec.id(), "first", "codex", 3, "planner"));
        TaskChange firstClaimed = service.claimNextTask(new ClaimTaskRequest("codex", "agent-a")).orElseThrow();
        TaskChange blocked = service.updateTaskStatus(new UpdateTaskStatusRequest(
                firstCreated.snapshot().task().id(), "agent-a", TaskStatus.BLOCKED, "waiting for review"));
        assertThat(blocked.snapshot().task().claimedBy()).isEqualTo("agent-a");
        assertThat(blocked.snapshot().task().blockedReason()).isEqualTo("waiting for review");

        TaskChange resetBlocked = service.updateTaskStatus(new UpdateTaskStatusRequest(
                firstCreated.snapshot().task().id(), "operator", TaskStatus.OPEN, null));
        assertThat(resetBlocked.snapshot().task().claimedBy()).isNull();
        assertThat(resetBlocked.snapshot().task().blockedReason()).isNull();
        service.claimNextTask(new ClaimTaskRequest("codex", "agent-b")).orElseThrow();
        TaskChange resetInProgress = service.updateTaskStatus(new UpdateTaskStatusRequest(
                firstCreated.snapshot().task().id(), "operator", TaskStatus.OPEN, null));
        assertThat(resetInProgress.snapshot().task().claimedBy()).isNull();
        TaskChange cancelledOpen = service.updateTaskStatus(new UpdateTaskStatusRequest(
                firstCreated.snapshot().task().id(), "operator", TaskStatus.CANCELLED, null));

        TaskChange secondCreated = service.enqueueTask(new EnqueueTaskRequest(
                spec.id(), "second", "codex", 2, "planner"));
        service.claimNextTask(new ClaimTaskRequest("codex", "agent-c")).orElseThrow();
        TaskChange cancelledInProgress = service.updateTaskStatus(new UpdateTaskStatusRequest(
                secondCreated.snapshot().task().id(), "operator", TaskStatus.CANCELLED, null));

        TaskChange thirdCreated = service.enqueueTask(new EnqueueTaskRequest(
                spec.id(), "third", "codex", 1, "planner"));
        service.claimNextTask(new ClaimTaskRequest("codex", "agent-d")).orElseThrow();
        service.updateTaskStatus(new UpdateTaskStatusRequest(
                thirdCreated.snapshot().task().id(), "agent-d", TaskStatus.BLOCKED, "dependency unavailable"));
        TaskChange cancelledBlocked = service.updateTaskStatus(new UpdateTaskStatusRequest(
                thirdCreated.snapshot().task().id(), "operator", TaskStatus.CANCELLED, null));

        assertThat(firstClaimed.snapshot().task().status()).isEqualTo(TaskStatus.IN_PROGRESS);
        assertThat(cancelledOpen.snapshot().task().status()).isEqualTo(TaskStatus.CANCELLED);
        assertThat(cancelledInProgress.snapshot().task().status()).isEqualTo(TaskStatus.CANCELLED);
        assertThat(cancelledInProgress.snapshot().task().claimedBy()).isEqualTo("agent-c");
        assertThat(cancelledBlocked.snapshot().task().status()).isEqualTo(TaskStatus.CANCELLED);
        assertThat(cancelledBlocked.snapshot().task().claimedBy()).isEqualTo("agent-d");
        assertThat(cancelledBlocked.snapshot().task().blockedReason()).isEqualTo("dependency unavailable");
        assertThat(service.listTasks(new TaskQuery(PROJECT, "codex", TaskStatus.CANCELLED))).hasSize(3);
        assertThat(service.getTask(firstCreated.snapshot().task().id()).task().status())
                .isEqualTo(TaskStatus.CANCELLED);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM agent_sessions", Integer.class)).isZero();

        ArgumentCaptor<WorkflowTaskChanged> frames = ArgumentCaptor.forClass(WorkflowTaskChanged.class);
        verify(publication, org.mockito.Mockito.times(14)).taskChanged(frames.capture());
        assertThat(frames.getAllValues())
                .extracting(WorkflowTaskChanged::transitionType)
                .containsExactly(
                        "task.created", "task.claimed", "task.blocked", "task.reset",
                        "task.claimed", "task.reset", "task.cancelled",
                        "task.created", "task.claimed", "task.cancelled",
                        "task.created", "task.claimed", "task.blocked", "task.cancelled");
        assertThat(frames.getAllValues())
                .allSatisfy(frame -> {
                    assertThat(frame.transitionId()).isNotBlank();
                    assertThat(frame.observedAt()).isNotBlank();
                    assertThat(frame.task().id()).isNotBlank();
                });
    }

    @ParameterizedTest(name = "rejects {0} -> {1}")
    @MethodSource("rejectedTransitions")
    void everyRejectedTransitionReturnsTypedErrorWithoutMutation(TaskStatus currentStatus, TaskStatus targetStatus) {
        Task before = seedTaskAt(currentStatus);
        int eventsBefore = repository.eventsForTask(before.id()).size();

        TaskDomainException error = catchThrowableOfType(
                () -> service.updateTaskStatus(new UpdateTaskStatusRequest(
                        before.id(),
                        "agent-a",
                        targetStatus,
                        targetStatus == TaskStatus.BLOCKED ? "reason" : null)),
                TaskDomainException.class);

        assertThat(error.code()).isEqualTo(TaskErrorCode.INVALID_TRANSITION);
        assertThat(error.currentStatus()).isEqualTo(currentStatus);
        assertThat(error.targetStatus()).isEqualTo(targetStatus);
        assertThat(service.getTask(before.id()).task()).isEqualTo(before);
        assertThat(repository.eventsForTask(before.id())).hasSize(eventsBefore);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM agent_sessions", Integer.class)).isZero();
        verifyNoInteractions(publication);
    }

    @Test
    void blockedRequiresNonblankReasonAndLeavesTaskAndEventsUntouched() {
        Task before = seedTaskAt(TaskStatus.IN_PROGRESS);
        int eventsBefore = repository.eventsForTask(before.id()).size();

        TaskDomainException error = catchThrowableOfType(
                () -> service.updateTaskStatus(new UpdateTaskStatusRequest(
                        before.id(), "agent-a", TaskStatus.BLOCKED, " \n ")),
                TaskDomainException.class);

        assertThat(error.code()).isEqualTo(TaskErrorCode.VALIDATION_FAILED);
        assertThat(service.getTask(before.id()).task()).isEqualTo(before);
        assertThat(repository.eventsForTask(before.id())).hasSize(eventsBefore);
        verifyNoInteractions(publication);
    }

    @Test
    void completionCapturesOneRecallableHandoffLinksItAndPublishesCompletedFrame() throws Exception {
        TaskSpec spec = createSpec();
        Task task = service.enqueueTask(new EnqueueTaskRequest(
                spec.id(), "complete me", "codex", 1, "planner")).snapshot().task();
        service.claimNextTask(new ClaimTaskRequest("codex", "worker-17")).orElseThrow();
        clearInvocations(publication);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM agent_sessions", Integer.class)).isZero();

        TaskChange completed = service.completeTask(new CompleteTaskRequest(
                task.id(),
                "worker-17",
                "codex",
                "codex-session-17",
                "Implemented the queue lifecycle service.",
                List.of("REST exposure remains"),
                "Expose the validated service through REST and MCP."));

        Task completedTask = completed.snapshot().task();
        assertThat(completedTask.status()).isEqualTo(TaskStatus.DONE);
        assertThat(completedTask.claimedBy()).isEqualTo("worker-17");
        assertThat(completedTask.resultHandoffId()).isNotBlank();
        assertThat(completed.event().type()).isEqualTo(TaskEventType.COMPLETED);
        assertThat(completed.event().detail())
                .containsEntry("handoffId", completedTask.resultHandoffId());
        assertThat(repository.eventsForTask(task.id()))
                .filteredOn(event -> event.type() == TaskEventType.COMPLETED)
                .singleElement()
                .extracting(TaskEvent::id)
                .isEqualTo(completed.event().id());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM agent_sessions", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_events WHERE event_type = 'Handoff'", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT id FROM agent_events WHERE event_type = 'Handoff'", String.class))
                .isEqualTo(completedTask.resultHandoffId());

        RecallResult recall = contextService.recall(PROJECT, 168, List.of("handoff"));
        assertThat(recall.items()).singleElement();
        RecalledItem handoff = recall.items().getFirst();
        assertThat(handoff.eventId()).isEqualTo(completedTask.resultHandoffId());
        assertThat(handoff.source()).isEqualTo("codex");
        assertThat(handoff.clientSessionId()).isEqualTo("codex-session-17");
        assertThat(handoff.repo()).isEqualTo(PROJECT);
        assertThat(handoff.headline()).isEqualTo("Implemented the queue lifecycle service.");
        assertThat(handoff.openLoops()).containsExactly("REST exposure remains");
        assertThat(handoff.nextAction()).isEqualTo("Expose the validated service through REST and MCP.");

        ArgumentCaptor<WorkflowTaskChanged> frame = ArgumentCaptor.forClass(WorkflowTaskChanged.class);
        verify(publication).taskChanged(frame.capture());
        assertThat(frame.getValue().transitionType()).isEqualTo("task.completed");
        assertThat(frame.getValue().transitionId()).isEqualTo(completed.event().id());
        assertThat(frame.getValue().task()).isEqualTo(completedTask);

        System.out.println("SAMPLE_COMPLETED_TASK_JSON=" + objectMapper.writeValueAsString(completedTask));
        System.out.println("SAMPLE_RECALLED_HANDOFF_JSON=" + objectMapper.writeValueAsString(handoff));
    }

    @Test
    void completionRejectsWrongClaimantAndNonInProgressStateWithoutHandoff() {
        TaskSpec spec = createSpec();
        Task task = service.enqueueTask(new EnqueueTaskRequest(
                spec.id(), "owned task", "codex", 1, "planner")).snapshot().task();
        service.claimNextTask(new ClaimTaskRequest("codex", "owner")).orElseThrow();
        clearInvocations(publication);
        int eventsBefore = repository.eventsForTask(task.id()).size();

        TaskDomainException wrongOwner = catchThrowableOfType(
                () -> service.completeTask(completionRequest(task.id(), "intruder")),
                TaskDomainException.class);
        assertThat(wrongOwner.code()).isEqualTo(TaskErrorCode.CLAIMANT_MISMATCH);
        assertThat(service.getTask(task.id()).task().status()).isEqualTo(TaskStatus.IN_PROGRESS);
        assertThat(repository.eventsForTask(task.id())).hasSize(eventsBefore);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM agent_sessions", Integer.class)).isZero();

        jdbcTemplate.update("""
                UPDATE tasks
                   SET status = 'open', claimed_by = NULL, blocked_reason = NULL
                 WHERE id = ?
                """, task.id());
        TaskDomainException wrongState = catchThrowableOfType(
                () -> service.completeTask(completionRequest(task.id(), "owner")),
                TaskDomainException.class);
        assertThat(wrongState.code()).isEqualTo(TaskErrorCode.INVALID_TRANSITION);
        assertThat(repository.eventsForTask(task.id())).hasSize(eventsBefore);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM agent_sessions", Integer.class)).isZero();
        verifyNoInteractions(publication);
    }

    @Test
    void handoffFailureRollsBackSessionAndLeavesTaskInProgressWithoutCompletionEvent() {
        TaskSpec spec = createSpec();
        Task task = service.enqueueTask(new EnqueueTaskRequest(
                spec.id(), "failure task", "codex", 1, "planner")).snapshot().task();
        service.claimNextTask(new ClaimTaskRequest("codex", "owner")).orElseThrow();
        clearInvocations(publication);
        int eventsBefore = repository.eventsForTask(task.id()).size();
        jdbcTemplate.execute("""
                CREATE TRIGGER fail_handoff_event_insert
                BEFORE INSERT ON agent_events
                WHEN NEW.event_type = 'Handoff'
                BEGIN
                    SELECT RAISE(ABORT, 'forced handoff failure');
                END
                """);

        TaskDomainException error = catchThrowableOfType(
                () -> service.completeTask(completionRequest(task.id(), "owner")),
                TaskDomainException.class);

        assertThat(error.code()).isEqualTo(TaskErrorCode.HANDOFF_FAILED);
        Task afterFailure = service.getTask(task.id()).task();
        assertThat(afterFailure.status()).isEqualTo(TaskStatus.IN_PROGRESS);
        assertThat(afterFailure.claimedBy()).isEqualTo("owner");
        assertThat(afterFailure.resultHandoffId()).isNull();
        assertThat(repository.eventsForTask(task.id())).hasSize(eventsBefore);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM agent_sessions", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM agent_events", Integer.class)).isZero();
        verifyNoInteractions(publication);
    }

    @Test
    void broadcasterFailureIsLoggedAndCannotRollBackCommittedMutation() {
        TaskSpec spec = createSpec();
        doThrow(new IllegalStateException("forced broadcaster failure"))
                .when(publication)
                .taskChanged(any(WorkflowTaskChanged.class));

        TaskChange created = service.enqueueTask(new EnqueueTaskRequest(
                spec.id(), "durable task", "codex", 1, "planner"));

        assertThat(service.getTask(created.snapshot().task().id()).task().status()).isEqualTo(TaskStatus.OPEN);
        assertThat(repository.eventsForTask(created.snapshot().task().id()))
                .extracting(TaskEvent::type)
                .containsExactly(TaskEventType.CREATED);
        verify(publication).taskChanged(any(WorkflowTaskChanged.class));
    }

    private TaskSpec createSpec() {
        return service.createSpec(new CreateSpecRequest(
                PROJECT,
                "Queue lifecycle",
                "Implement and verify the lifecycle contract.",
                Map.of("repo", "black-box", "path", "docs/spec.md", "sha", "abc123"),
                "planner"));
    }

    private Task seedTaskAt(TaskStatus status) {
        TaskSpec spec = createSpec();
        Task task = service.enqueueTask(new EnqueueTaskRequest(
                spec.id(), "matrix task", "codex", 1, "planner")).snapshot().task();
        String claimedBy = switch (status) {
            case CLAIMED, IN_PROGRESS, BLOCKED, DONE -> "agent-a";
            default -> null;
        };
        String blockedReason = status == TaskStatus.BLOCKED ? "waiting" : null;
        jdbcTemplate.update("""
                UPDATE tasks
                   SET status = ?, claimed_by = ?, blocked_reason = ?
                 WHERE id = ?
                """, status.value(), claimedBy, blockedReason, task.id());
        Task seeded = service.getTask(task.id()).task();
        clearInvocations(publication);
        return seeded;
    }

    private static CompleteTaskRequest completionRequest(String taskId, String actor) {
        return new CompleteTaskRequest(
                taskId,
                actor,
                "codex",
                "completion-session",
                "Completed the task.",
                List.of("follow-up remains"),
                "Start the follow-up.");
    }

    private static Stream<Arguments> rejectedTransitions() {
        List<Arguments> transitions = new ArrayList<>();
        for (TaskStatus current : TaskStatus.values()) {
            for (TaskStatus target : TaskStatus.values()) {
                if (!isAllowedStatusUpdate(current, target)) {
                    transitions.add(Arguments.of(current, target));
                }
            }
        }
        return transitions.stream();
    }

    private static boolean isAllowedStatusUpdate(TaskStatus current, TaskStatus target) {
        return (current == TaskStatus.IN_PROGRESS && target == TaskStatus.BLOCKED)
                || ((current == TaskStatus.IN_PROGRESS || current == TaskStatus.BLOCKED)
                        && target == TaskStatus.OPEN)
                || ((current == TaskStatus.OPEN
                        || current == TaskStatus.IN_PROGRESS
                        || current == TaskStatus.BLOCKED)
                        && target == TaskStatus.CANCELLED);
    }
}
