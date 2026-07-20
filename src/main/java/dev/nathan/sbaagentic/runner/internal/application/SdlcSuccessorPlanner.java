package dev.nathan.sbaagentic.runner.internal.application;

import java.util.List;
import java.util.Optional;

import dev.nathan.sbaagentic.runner.SdlcTaskChainer;
import dev.nathan.sbaagentic.runner.gate.StoryFrontmatterParser;
import dev.nathan.sbaagentic.runner.internal.application.ApprovalInterpreter.Approval;
import dev.nathan.sbaagentic.runner.internal.client.blackbox.BlackBoxApiClient;
import dev.nathan.sbaagentic.runner.internal.client.blackbox.Task;
import dev.nathan.sbaagentic.runner.internal.client.blackbox.TaskEvent;
import dev.nathan.sbaagentic.runner.internal.client.blackbox.TaskSnapshot;
import dev.nathan.sbaagentic.runner.internal.client.blackbox.TaskSpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SdlcSuccessorPlanner {

    private static final Logger log = LoggerFactory.getLogger(SdlcSuccessorPlanner.class);
    private static final String BUILD_LANE = "auto";
    private static final String REVIEW_LANE = "sdlc:review";

    private final BlackBoxApiClient apiClient;
    private final StoryFrontmatterParser frontmatterParser;
    private final SdlcTaskChainer taskChainer;
    private final ApprovalInterpreter approvalInterpreter;
    private final SdlcReconciliationState state;

    public SdlcSuccessorPlanner(
            BlackBoxApiClient apiClient,
            StoryFrontmatterParser frontmatterParser,
            SdlcTaskChainer taskChainer,
            ApprovalInterpreter approvalInterpreter,
            SdlcReconciliationState state) {
        this.apiClient = apiClient;
        this.frontmatterParser = frontmatterParser;
        this.taskChainer = taskChainer;
        this.approvalInterpreter = approvalInterpreter;
        this.state = state;
    }

    public void reconcilePlan(
            Task task,
            TaskSpec spec,
            List<TaskEvent> events,
            String actorId) {
        if (state.existingBuildTask(spec.id()).isPresent()
                || approvalInterpreter.hasRunnerMarker(
                        events, actorId, ApprovalInterpreter.REJECTION_RECORDED)) {
            return;
        }
        Optional<Approval> rejection = approvalInterpreter.latestRejection(events, "plan");
        if (rejection.isPresent()) {
            approvalInterpreter.recordRejection(task, rejection.orElseThrow(), actorId);
            return;
        }
        Optional<Approval> approval = approvalInterpreter.latestApproval(events, "plan");
        if (approval.isEmpty() || !"approve".equals(approval.orElseThrow().decision())) {
            return;
        }
        if (approvalInterpreter.latestWorkerAnnotationText(events, "plan").isEmpty()) {
            log.warn(
                    "SDLC plan task {} is approved without a worker plan annotation; refusing to enqueue build",
                    task.id());
            return;
        }
        taskChainer.ensureTask(spec.id(), task.title(), BUILD_LANE, task.priority(), actorId);
    }

    public void reconcileBuildSuccessors(String actorId) {
        for (TaskSnapshot snapshot : safeList(apiClient.listTasks("done", BUILD_LANE))) {
            if (snapshot == null || snapshot.task() == null) {
                continue;
            }
            Task build = snapshot.task();
            try {
                TaskSpec spec = snapshot.spec() == null ? apiClient.getSpec(build.specId()) : snapshot.spec();
                Optional<StoryFrontmatterParser.ParsedStory> parsed = frontmatterParser.parse(spec.body());
                if (parsed.isEmpty() || !"sdlc".equals(parsed.orElseThrow().frontmatter().mode())) {
                    continue;
                }
                List<TaskEvent> events = safeList(apiClient.taskEvents(build.id()));
                if (state.latestBuildState(events, actorId, build).isEmpty()
                        || !approvalInterpreter.hasWorkerDone(events)) {
                    continue;
                }
                taskChainer.ensureTask(spec.id(), build.title(), REVIEW_LANE, build.priority(), actorId);
            }
            catch (RuntimeException ex) {
                log.warn("Unable to reconcile SDLC review successor for build {}", build.id(), ex);
            }
        }
    }

    private static <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }
}
