package dev.nathan.sbaagentic.runner.internal.application;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import dev.nathan.sbaagentic.runner.RepoConfig;
import dev.nathan.sbaagentic.runner.RunnerConfig;
import dev.nathan.sbaagentic.runner.gate.StoryFrontmatter;
import dev.nathan.sbaagentic.runner.internal.application.ApprovalInterpreter.Approval;
import dev.nathan.sbaagentic.runner.internal.application.ApprovalInterpreter.ShipMarker;
import dev.nathan.sbaagentic.runner.internal.application.SdlcReconciliationState.BuildState;
import dev.nathan.sbaagentic.runner.internal.client.blackbox.Task;
import dev.nathan.sbaagentic.runner.internal.client.blackbox.TaskEvent;
import dev.nathan.sbaagentic.runner.internal.client.blackbox.TaskSpec;
import dev.nathan.sbaagentic.runner.ship.ShipExecutor;
import dev.nathan.sbaagentic.runner.ship.ShipExecutor.ShipResult;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ApprovedReviewShipper {

    private static final Logger log = LoggerFactory.getLogger(ApprovedReviewShipper.class);

    private final ShipExecutor shipExecutor;
    private final ApprovalInterpreter approvalInterpreter;
    private final SdlcReconciliationState state;

    public ApprovedReviewShipper(
            ShipExecutor shipExecutor,
            ApprovalInterpreter approvalInterpreter,
            SdlcReconciliationState state) {
        this.shipExecutor = shipExecutor;
        this.approvalInterpreter = approvalInterpreter;
        this.state = state;
    }

    public void reconcileReview(
            Task task,
            TaskSpec spec,
            StoryFrontmatter frontmatter,
            List<TaskEvent> events,
            RunnerConfig config,
            String actorId) {
        Optional<ShipMarker> shipMarker = approvalInterpreter.latestShipMarker(events, actorId);
        if (shipMarker.isPresent()) {
            state.pruneMergedWorktreeIfNeeded(
                    task.id(), actorId, frontmatter.repo(), config, shipMarker.orElseThrow());
            return;
        }
        if (approvalInterpreter.hasRunnerMarker(
                events, actorId, ApprovalInterpreter.REJECTION_RECORDED)) {
            return;
        }

        Optional<Approval> rejection = approvalInterpreter.latestRejection(events, "review");
        if (rejection.isPresent()) {
            approvalInterpreter.recordRejection(task, rejection.orElseThrow(), actorId);
            return;
        }

        Optional<Approval> approval = approvalInterpreter.latestApproval(events, "review");
        if (approval.isEmpty() || !"approve".equals(approval.orElseThrow().decision())) {
            return;
        }
        Optional<String> reviewSummary = approvalInterpreter.latestWorkerAnnotationText(events, "review");
        if (reviewSummary.isEmpty()) {
            log.warn(
                    "SDLC review task {} is approved without a worker review annotation; refusing to ship",
                    task.id());
            return;
        }

        Optional<RepoConfig> repoConfig = state.matchingRepo(config, frontmatter.repo());
        Optional<BuildState> buildState = state.findBuildState(spec.id(), actorId);
        if (repoConfig.isEmpty() || buildState.isEmpty()) {
            log.warn(
                    "SDLC review task {} is approved, but its configured repo or preserved build "
                            + "worktree is unavailable; refusing to ship",
                    task.id());
            return;
        }

        BuildState build = buildState.orElseThrow();
        RepoConfig repo = repoConfig.orElseThrow();
        Optional<Path> worktree = state.validatedWorktree(repo, build, true);
        if (worktree.isEmpty()) {
            log.warn(
                    "SDLC review task {} is approved, but preserved worktree {} failed validation; "
                            + "refusing to ship",
                    task.id(), build.worktree());
            return;
        }

        ShipResult result = shipExecutor.shipForSdlc(
                task.id(),
                actorId,
                repo,
                build.branch(),
                worktree.orElseThrow().toFile(),
                task.title(),
                reviewSummary.orElseThrow(),
                approval.orElseThrow().id());
        if ("merged".equals(result.status())) {
            state.pruneMergedWorktree(task.id(), actorId, repo, worktree.orElseThrow());
        }
    }
}
