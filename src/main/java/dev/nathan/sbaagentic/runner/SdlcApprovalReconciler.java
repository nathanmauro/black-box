package dev.nathan.sbaagentic.runner;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import dev.nathan.sbaagentic.runner.gate.StoryFrontmatterParser;
import dev.nathan.sbaagentic.runner.internal.application.ApprovalInterpreter;
import dev.nathan.sbaagentic.runner.internal.application.ApprovedReviewShipper;
import dev.nathan.sbaagentic.runner.internal.application.SdlcReconciliationState;
import dev.nathan.sbaagentic.runner.internal.application.SdlcSuccessorPlanner;
import dev.nathan.sbaagentic.runner.internal.client.blackbox.BlackBoxApiClient;
import dev.nathan.sbaagentic.runner.internal.client.blackbox.Task;
import dev.nathan.sbaagentic.runner.internal.client.blackbox.TaskEvent;
import dev.nathan.sbaagentic.runner.internal.client.blackbox.TaskSnapshot;
import dev.nathan.sbaagentic.runner.internal.client.blackbox.TaskSpec;
import dev.nathan.sbaagentic.runner.internal.client.blackbox.TaskStatus;
import dev.nathan.sbaagentic.runner.process.ProcessRunner;
import dev.nathan.sbaagentic.runner.ship.ShipExecutor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class SdlcApprovalReconciler {

    private static final Logger log = LoggerFactory.getLogger(SdlcApprovalReconciler.class);
    private static final String PLAN_LANE = "sdlc:plan";
    private static final String REVIEW_LANE = "sdlc:review";

    private final BlackBoxApiClient apiClient;
    private final StoryFrontmatterParser frontmatterParser;
    private final SdlcSuccessorPlanner successorPlanner;
    private final ApprovedReviewShipper reviewShipper;
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    @Autowired
    public SdlcApprovalReconciler(
            BlackBoxApiClient apiClient,
            StoryFrontmatterParser frontmatterParser,
            ShipExecutor shipExecutor,
            ProcessRunner processRunner,
            SdlcTaskChainer taskChainer) {
        this.apiClient = apiClient;
        this.frontmatterParser = frontmatterParser;
        ApprovalInterpreter approvalInterpreter = new ApprovalInterpreter(apiClient);
        SdlcReconciliationState state = new SdlcReconciliationState(apiClient, processRunner);
        this.successorPlanner = new SdlcSuccessorPlanner(
                apiClient, frontmatterParser, taskChainer, approvalInterpreter, state);
        this.reviewShipper = new ApprovedReviewShipper(shipExecutor, approvalInterpreter, state);
    }

    public SdlcApprovalReconciler(
            BlackBoxApiClient apiClient,
            StoryFrontmatterParser frontmatterParser,
            ShipExecutor shipExecutor,
            ProcessRunner processRunner) {
        this(apiClient, frontmatterParser, shipExecutor, processRunner, new SdlcTaskChainer(apiClient));
    }

    public void reconcile(RunnerConfig config, String actorId) {
        successorPlanner.reconcileBuildSuccessors(actorId);
        reconcileLane(PLAN_LANE, config, actorId);
        reconcileLane(REVIEW_LANE, config, actorId);
    }

    public void reconcileTask(String taskId, RunnerConfig config, String actorId) {
        if (taskId == null || taskId.isBlank()) {
            return;
        }
        for (String lane : List.of(PLAN_LANE, REVIEW_LANE)) {
            Optional<TaskSnapshot> matching = safeList(apiClient.listTasks("done", lane)).stream()
                    .filter(Objects::nonNull)
                    .filter(snapshot -> snapshot.task() != null)
                    .filter(snapshot -> taskId.equals(snapshot.task().id()))
                    .findFirst();
            if (matching.isPresent()) {
                reconcileSnapshot(matching.orElseThrow(), config, actorId);
                return;
            }
        }
    }

    private void reconcileLane(String lane, RunnerConfig config, String actorId) {
        for (TaskSnapshot snapshot : safeList(apiClient.listTasks("done", lane))) {
            if (snapshot == null || snapshot.task() == null) {
                continue;
            }
            try {
                reconcileSnapshot(snapshot, config, actorId);
            }
            catch (RuntimeException ex) {
                log.warn("Unable to reconcile SDLC approval for task {}", snapshot.task().id(), ex);
            }
        }
    }

    private void reconcileSnapshot(TaskSnapshot snapshot, RunnerConfig config, String actorId) {
        Task task = snapshot.task();
        if (task.status() != TaskStatus.DONE
                || (!PLAN_LANE.equals(task.lane()) && !REVIEW_LANE.equals(task.lane()))
                || !inFlight.add(task.id())) {
            return;
        }
        try {
            TaskSpec spec = snapshot.spec() == null ? apiClient.getSpec(task.specId()) : snapshot.spec();
            Optional<StoryFrontmatterParser.ParsedStory> parsed = frontmatterParser.parse(spec.body());
            if (parsed.isEmpty() || !"sdlc".equals(parsed.orElseThrow().frontmatter().mode())) {
                return;
            }
            List<TaskEvent> events = safeList(apiClient.taskEvents(task.id()));
            if (PLAN_LANE.equals(task.lane())) {
                successorPlanner.reconcilePlan(task, spec, events, actorId);
            }
            else {
                reviewShipper.reconcileReview(
                        task,
                        spec,
                        parsed.orElseThrow().frontmatter(),
                        events,
                        config,
                        actorId);
            }
        }
        finally {
            inFlight.remove(task.id());
        }
    }

    private static <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }
}
