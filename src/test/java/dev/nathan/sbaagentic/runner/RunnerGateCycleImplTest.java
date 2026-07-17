package dev.nathan.sbaagentic.runner;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import dev.nathan.sbaagentic.runner.gate.GateEvaluator;
import dev.nathan.sbaagentic.runner.gate.GateResult;
import dev.nathan.sbaagentic.task.SpecStatus;
import dev.nathan.sbaagentic.task.Task;
import dev.nathan.sbaagentic.task.TaskChange;
import dev.nathan.sbaagentic.task.TaskSnapshot;
import dev.nathan.sbaagentic.task.TaskSpec;
import dev.nathan.sbaagentic.task.TaskStatus;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RunnerGateCycleImplTest {

    private static final String ACTOR_ID = "blackbox-runner";
    private static final String TASK_ID = "task-1";
    private static final String SPEC_ID = "spec-1";
    private static final RunnerConfig CONFIG = new RunnerConfig(1, List.of(), null, List.of());

    @Mock
    BlackBoxApiClient apiClient;

    @Mock
    GateEvaluator gateEvaluator;

    private RunnerGateCycleImpl gateCycle;

    @BeforeEach
    void setUp() {
        gateCycle = new RunnerGateCycleImpl(apiClient, gateEvaluator);
    }

    @Test
    void passingGateEnqueuesAnnotatesAndCompletesOnce() {
        TaskChange claimedTask = claimedGateTask();
        when(gateEvaluator.evaluate(claimedTask.snapshot().spec(), CONFIG))
                .thenReturn(new GateResult(true, List.of(), "mvn test", null, "full_auto"));

        gateCycle.evaluate(claimedTask, CONFIG, ACTOR_ID);

        verify(apiClient, times(1))
                .enqueueTask(SPEC_ID, "Implement story", "auto", 10, ACTOR_ID);
        verify(apiClient, times(1)).annotate(
                TASK_ID,
                ACTOR_ID,
                "progress",
                "Gate passed: all checks green | resolved verify: mvn test",
                Map.of("resolvedVerify", "mvn test"));
        verify(apiClient, times(1)).completeTask(
                TASK_ID,
                ACTOR_ID,
                "cli",
                "blackbox-runner-gate-" + TASK_ID,
                "Gate passed; auto task enqueued.",
                List.of(),
                "Auto-lane execution will pick this up next.");
        verify(apiClient, never()).updateTaskStatus(any(), any(), any(), any());
    }

    @Test
    void passingSdlcGateEnqueuesPlanLane() {
        TaskChange claimedTask = claimedGateTask();
        when(gateEvaluator.evaluate(claimedTask.snapshot().spec(), CONFIG))
                .thenReturn(new GateResult(true, List.of(), "mvn test", null, "sdlc"));

        gateCycle.evaluate(claimedTask, CONFIG, ACTOR_ID);

        verify(apiClient, times(1))
                .enqueueTask(SPEC_ID, "Implement story", "sdlc:plan", 10, ACTOR_ID);
        verify(apiClient, never())
                .enqueueTask(SPEC_ID, "Implement story", "auto", 10, ACTOR_ID);
        verify(apiClient, times(1)).completeTask(
                TASK_ID,
                ACTOR_ID,
                "cli",
                "blackbox-runner-gate-" + TASK_ID,
                "Gate passed; SDLC plan task enqueued.",
                List.of(),
                "SDLC plan-lane execution will pick this up next.");
    }

    @Test
    void passingSdlcGateDeduplicatesAgainstPlanLaneOnly() {
        TaskChange claimedTask = claimedGateTask();
        when(gateEvaluator.evaluate(claimedTask.snapshot().spec(), CONFIG))
                .thenReturn(new GateResult(true, List.of(), "mvn test", null, "sdlc"));
        when(apiClient.listTasks(null, "sdlc:plan")).thenReturn(List.of(
                existingTask("auto-task-1", "auto"),
                existingTask("plan-task-1", "sdlc:plan")));

        gateCycle.evaluate(claimedTask, CONFIG, ACTOR_ID);

        verify(apiClient, never()).enqueueTask(any(), any(), any(), anyInt(), any());
        verify(apiClient).annotate(
                TASK_ID,
                ACTOR_ID,
                "progress",
                "Gate passed; existing SDLC plan task plan-task-1 reused.",
                Map.of("planTaskId", "plan-task-1"));
        verify(apiClient).completeTask(
                TASK_ID,
                ACTOR_ID,
                "cli",
                "blackbox-runner-gate-" + TASK_ID,
                "Gate passed; existing SDLC plan task reused.",
                List.of(),
                "SDLC plan-lane execution will pick this up next.");
    }

    @Test
    void postEnqueueAnnotationFailureDoesNotReleaseGateTask() {
        TaskChange claimedTask = claimedGateTask();
        when(gateEvaluator.evaluate(claimedTask.snapshot().spec(), CONFIG))
                .thenReturn(new GateResult(true, List.of(), "mvn test", null, "full_auto"));
        when(apiClient.annotate(
                eq(TASK_ID),
                eq(ACTOR_ID),
                eq("progress"),
                any(),
                eq(Map.of("resolvedVerify", "mvn test"))))
                .thenThrow(new RuntimeException("annotation unavailable"));

        gateCycle.evaluate(claimedTask, CONFIG, ACTOR_ID);

        verify(apiClient, times(1))
                .enqueueTask(SPEC_ID, "Implement story", "auto", 10, ACTOR_ID);
        verify(apiClient, never()).updateTaskStatus(any(), any(), any(), any());
        verify(apiClient, never()).completeTask(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void completeFailureThenReevaluationReusesExistingAutoTask() {
        TaskChange claimedTask = claimedGateTask();
        when(gateEvaluator.evaluate(claimedTask.snapshot().spec(), CONFIG))
                .thenReturn(new GateResult(true, List.of(), "mvn test", null, "full_auto"));
        when(apiClient.listTasks(null, "auto"))
                .thenReturn(List.of(), List.of(existingTask("auto-task-1", "auto")));
        when(apiClient.completeTask(
                TASK_ID,
                ACTOR_ID,
                "cli",
                "blackbox-runner-gate-" + TASK_ID,
                "Gate passed; auto task enqueued.",
                List.of(),
                "Auto-lane execution will pick this up next."))
                .thenThrow(new RuntimeException("completion unavailable"));

        gateCycle.evaluate(claimedTask, CONFIG, ACTOR_ID);
        gateCycle.evaluate(claimedTask, CONFIG, ACTOR_ID);

        verify(apiClient, times(1))
                .enqueueTask(SPEC_ID, "Implement story", "auto", 10, ACTOR_ID);
        verify(apiClient).annotate(
                TASK_ID,
                ACTOR_ID,
                "progress",
                "Gate passed; existing auto task auto-task-1 reused.",
                Map.of("autoTaskId", "auto-task-1"));
    }

    @Test
    void blockedGateMarksTaskBlockedWithoutEnqueuing() {
        TaskChange claimedTask = claimedGateTask();
        when(gateEvaluator.evaluate(claimedTask.snapshot().spec(), CONFIG))
                .thenReturn(new GateResult(
                        false, List.of("some finding"), null, null, "full_auto"));

        gateCycle.evaluate(claimedTask, CONFIG, ACTOR_ID);

        verify(apiClient).updateTaskStatus(TASK_ID, ACTOR_ID, "blocked", "some finding");
        verify(apiClient).annotate(
                TASK_ID,
                ACTOR_ID,
                "progress",
                "Gate blocked: 1 issue(s) found.",
                Map.of("findings", List.of("some finding")));
        verify(apiClient, never()).enqueueTask(any(), any(), any(), anyInt(), any());
    }

    @Test
    void evaluationFailureReleasesGateTaskToOpen() {
        TaskChange claimedTask = claimedGateTask();
        when(gateEvaluator.evaluate(claimedTask.snapshot().spec(), CONFIG))
                .thenThrow(new RuntimeException("evaluation failed"));

        gateCycle.evaluate(claimedTask, CONFIG, ACTOR_ID);

        verify(apiClient).updateTaskStatus(
                TASK_ID, ACTOR_ID, "open", "Gate evaluation crashed: evaluation failed");
        verify(apiClient, never()).enqueueTask(any(), any(), any(), anyInt(), any());
    }

    private static TaskChange claimedGateTask() {
        Instant now = Instant.parse("2026-07-15T12:00:00Z");
        Task task = new Task(
                TASK_ID,
                SPEC_ID,
                "/tmp/project",
                "Implement story",
                "gate",
                TaskStatus.IN_PROGRESS,
                10,
                "test",
                ACTOR_ID,
                null,
                null,
                now,
                now);
        TaskSpec spec = new TaskSpec(
                SPEC_ID,
                "/tmp/project",
                "Story",
                "# Story",
                null,
                SpecStatus.ACTIVE,
                "test",
                now,
                now);
        return new TaskChange(new TaskSnapshot(task, spec), null);
    }

    private static TaskSnapshot existingTask(String taskId, String lane) {
        Instant now = Instant.parse("2026-07-15T12:01:00Z");
        Task task = new Task(
                taskId,
                SPEC_ID,
                "/tmp/project",
                "Implement story",
                lane,
                TaskStatus.OPEN,
                10,
                ACTOR_ID,
                null,
                null,
                null,
                now,
                now);
        return new TaskSnapshot(task, null);
    }
}
