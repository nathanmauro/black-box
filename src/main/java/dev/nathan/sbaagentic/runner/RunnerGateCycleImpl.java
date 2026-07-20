package dev.nathan.sbaagentic.runner;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import dev.nathan.sbaagentic.runner.gate.GateEvaluator;
import dev.nathan.sbaagentic.runner.gate.GateResult;
import dev.nathan.sbaagentic.workflow.Task;
import dev.nathan.sbaagentic.workflow.TaskChange;
import dev.nathan.sbaagentic.workflow.TaskSnapshot;
import dev.nathan.sbaagentic.workflow.TaskSpec;
import dev.nathan.sbaagentic.workflow.TaskStatus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Component
@Primary
public class RunnerGateCycleImpl implements GateCycle {

    private static final Logger log = LoggerFactory.getLogger(RunnerGateCycleImpl.class);

    private final BlackBoxApiClient apiClient;
    private final GateEvaluator gateEvaluator;

    public RunnerGateCycleImpl(BlackBoxApiClient apiClient, GateEvaluator gateEvaluator) {
        this.apiClient = apiClient;
        this.gateEvaluator = gateEvaluator;
    }

    @Override
    public void evaluate(TaskChange claimedGateTask, RunnerConfig config, String actorId) {
        Task task = claimedGateTask.snapshot().task();
        try {
            TaskSpec spec = claimedGateTask.snapshot().spec();
            if (spec == null) {
                spec = apiClient.getSpec(task.specId());
            }

            GateResult result = gateEvaluator.evaluate(spec, config);
            if (result.pass()) {
                boolean sdlc = "sdlc".equals(result.mode());
                String successorLane = sdlc ? "sdlc:plan" : "auto";
                Optional<Task> existingSuccessorTask = existingTask(spec.id(), successorLane);
                if (existingSuccessorTask.isPresent()) {
                    Task existing = existingSuccessorTask.orElseThrow();
                    apiClient.annotate(
                            task.id(),
                            actorId,
                            "progress",
                            sdlc
                                    ? "Gate passed; existing SDLC plan task "
                                            + existing.id() + " reused."
                                    : "Gate passed; existing auto task "
                                            + existing.id() + " reused.",
                            Map.of(sdlc ? "planTaskId" : "autoTaskId", existing.id()));
                }
                else {
                    apiClient.enqueueTask(
                            spec.id(), task.title(), successorLane, task.priority(), actorId);
                }
                try {
                    List<String> findings = result.findings().isEmpty()
                            ? List.of("all checks green")
                            : result.findings();
                    String annotation = "Gate passed: " + String.join("; ", findings);
                    if (result.resolvedVerify() != null) {
                        annotation += " | resolved verify: " + result.resolvedVerify();
                    }
                    apiClient.annotate(
                            task.id(),
                            actorId,
                            "progress",
                            annotation,
                            Map.of("resolvedVerify", result.resolvedVerify()));
                    apiClient.completeTask(
                            task.id(),
                            actorId,
                            "cli",
                            "blackbox-runner-gate-" + task.id(),
                            existingSuccessorTask.isPresent()
                                    ? sdlc
                                            ? "Gate passed; existing SDLC plan task reused."
                                            : "Gate passed; existing auto task reused."
                                    : sdlc
                                            ? "Gate passed; SDLC plan task enqueued."
                                            : "Gate passed; auto task enqueued.",
                            List.of(),
                            sdlc
                                    ? "SDLC plan-lane execution will pick this up next."
                                    : "Auto-lane execution will pick this up next.");
                }
                catch (RuntimeException postEnqueueFailure) {
                    if (sdlc) {
                        log.error(
                                "SDLC plan task enqueued for spec {} but annotate/complete failed on gate task {} "
                                        + "afterward; leaving the gate task claimed rather than releasing it, to "
                                        + "avoid a duplicate sdlc:plan enqueue on re-evaluation. Manual cleanup "
                                        + "may be required.",
                                spec.id(), task.id(), postEnqueueFailure);
                    }
                    else {
                        log.error(
                                "Auto task enqueued for spec {} but annotate/complete failed on gate task {} "
                                        + "afterward; leaving the gate task claimed rather than releasing it, to "
                                        + "avoid a duplicate auto-lane enqueue on re-evaluation. Manual cleanup "
                                        + "may be required.",
                                spec.id(), task.id(), postEnqueueFailure);
                    }
                }
                return;
            }

            apiClient.updateTaskStatus(
                    task.id(), actorId, "blocked", String.join("\n", result.findings()));
            apiClient.annotate(
                    task.id(),
                    actorId,
                    "progress",
                    "Gate blocked: " + result.findings().size() + " issue(s) found.",
                    Map.of("findings", result.findings()));
        }
        catch (RuntimeException ex) {
            log.error("Gate evaluation failed for task {}; releasing it back to open", task.id(), ex);
            apiClient.updateTaskStatus(
                    task.id(), actorId, "open", "Gate evaluation crashed: " + ex.getMessage());
        }
    }

    private Optional<Task> existingTask(String specId, String lane) {
        return apiClient.listTasks(null, lane).stream()
                .map(TaskSnapshot::task)
                .filter(task -> task != null
                        && specId.equals(task.specId())
                        && lane.equals(task.lane())
                        && (task.status() == TaskStatus.OPEN
                                || task.status() == TaskStatus.IN_PROGRESS))
                .findFirst();
    }
}
