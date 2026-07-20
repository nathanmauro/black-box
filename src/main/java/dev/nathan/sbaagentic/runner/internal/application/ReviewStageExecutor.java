package dev.nathan.sbaagentic.runner.internal.application;

import java.util.List;
import java.util.Optional;

import dev.nathan.sbaagentic.runner.RunnerConfig;
import dev.nathan.sbaagentic.runner.RunnerNaming;
import dev.nathan.sbaagentic.runner.internal.application.RunContextLoader.StageContext;
import dev.nathan.sbaagentic.runner.internal.application.SdlcStateReader.BuildArtifact;
import dev.nathan.sbaagentic.runner.internal.application.WorktreeManager.GitState;
import dev.nathan.sbaagentic.runner.internal.client.blackbox.BlackBoxApiClient;
import dev.nathan.sbaagentic.runner.internal.client.blackbox.Task;
import dev.nathan.sbaagentic.runner.internal.client.blackbox.TaskChange;
import dev.nathan.sbaagentic.runner.internal.client.blackbox.TaskStatus;
import dev.nathan.sbaagentic.runner.run.GoalPromptBuilder;
import dev.nathan.sbaagentic.runner.run.RunStage;
import dev.nathan.sbaagentic.runner.run.WorkerRunExecutor;
import dev.nathan.sbaagentic.runner.run.WorkerRunExecutor.WorkerRunResult;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ReviewStageExecutor {

    private static final Logger log = LoggerFactory.getLogger(ReviewStageExecutor.class);

    private final BlackBoxApiClient apiClient;
    private final WorkerRunExecutor workerRunExecutor;
    private final GoalPromptBuilder goalPromptBuilder;
    private final RunContextLoader contextLoader;
    private final WorktreeManager worktreeManager;
    private final SdlcStateReader stateReader;

    public ReviewStageExecutor(
            BlackBoxApiClient apiClient,
            WorkerRunExecutor workerRunExecutor,
            GoalPromptBuilder goalPromptBuilder,
            RunContextLoader contextLoader,
            WorktreeManager worktreeManager,
            SdlcStateReader stateReader) {
        this.apiClient = apiClient;
        this.workerRunExecutor = workerRunExecutor;
        this.goalPromptBuilder = goalPromptBuilder;
        this.contextLoader = contextLoader;
        this.worktreeManager = worktreeManager;
        this.stateReader = stateReader;
    }

    public void execute(
            TaskChange claimedReviewTask,
            RunnerConfig config,
            String actorId,
            String orchestratorSessionId) {
        Task task = claimedReviewTask.snapshot().task();
        String tmuxSessionName = null;
        try {
            Optional<StageContext> loaded = contextLoader.loadSdlcContext(
                    claimedReviewTask, config, actorId, "SDLC review");
            if (loaded.isEmpty()) {
                return;
            }
            StageContext context = loaded.orElseThrow();
            Optional<Task> buildTask = stateReader.latestTask(context.spec().id(), "auto", TaskStatus.DONE);
            if (buildTask.isEmpty()) {
                block(task.id(), actorId, "SDLC review has no completed build task for its spec.");
                return;
            }
            Task build = buildTask.orElseThrow();
            Optional<BuildArtifact> artifact = stateReader.buildArtifact(build.id(), actorId);
            if (artifact.isEmpty()
                    || !worktreeManager.validPreservedWorktree(
                            context.repoDir(), build,
                            artifact.orElseThrow().branch(), artifact.orElseThrow().worktree())) {
                block(task.id(), actorId,
                        "SDLC review build artifact is missing or outside the configured repo worktrees.");
                return;
            }
            if (!stateReader.hasApprovedPlan(context.spec().id(), actorId)) {
                block(task.id(), actorId,
                        "SDLC review requires an approved, un-rejected plan for its spec.");
                return;
            }
            Optional<Task> planTask = stateReader.latestTask(
                    context.spec().id(), "sdlc:plan", TaskStatus.DONE);
            Optional<String> approvedPlan = planTask.flatMap(plan -> stateReader.latestWorkerAnnotationText(
                    plan.id(), "plan", null));
            if (approvedPlan.isEmpty()) {
                block(task.id(), actorId,
                        "SDLC review has no approved plan annotation for its spec.");
                return;
            }

            BuildArtifact buildArtifact = artifact.orElseThrow();
            GitState before = worktreeManager.gitState(buildArtifact.worktree());
            String prompt = goalPromptBuilder.buildReview(
                    task.id(),
                    context.spec().body(),
                    context.resolvedVerify(),
                    approvedPlan.orElseThrow());
            WorkerRunResult result = workerRunExecutor.execute(
                    task,
                    context.repoDir(),
                    buildArtifact.worktree(),
                    prompt,
                    config,
                    actorId,
                    orchestratorSessionId,
                    RunStage.REVIEW);
            tmuxSessionName = result.tmuxSessionName();
            switch (result.outcome()) {
                case NO_ENGINE -> block(task.id(), actorId, "No enabled engine configured");
                case REQUEUED -> {
                    return;
                }
                case TIMED_OUT -> block(task.id(), actorId, "Run timed out after 45m. " + result.detail());
                case BLOCKED -> block(task.id(), actorId, result.detail());
                case DONE -> {
                    Optional<String> review = stateReader.latestWorkerAnnotationText(
                            task.id(), "review", result.startedAt());
                    if (review.isEmpty()) {
                        block(task.id(), actorId,
                                "SDLC review worker reported done without a review annotation.");
                        return;
                    }
                    if (!before.equals(worktreeManager.gitState(buildArtifact.worktree()))) {
                        block(task.id(), actorId,
                                "SDLC review stage changed the worktree; review stages must be read-only.");
                        return;
                    }
                    apiClient.completeTask(
                            task.id(),
                            actorId,
                            "cli",
                            "blackbox-runner-review-" + task.id(),
                            review.orElseThrow(),
                            List.of(),
                            "Await human approval of the SDLC review.");
                }
            }
        }
        catch (RuntimeException ex) {
            log.error("SDLC review execution failed for task {}; releasing it back to open", task.id(), ex);
            releaseToOpenBestEffort(task.id(), actorId,
                    "SDLC review execution crashed: " + ex.getMessage());
        }
        finally {
            workerRunExecutor.killSessionBestEffort(tmuxSessionName);
            workerRunExecutor.finish(task.id(), RunnerNaming.tmuxSessionName(task.id()));
        }
    }

    private void releaseToOpenBestEffort(String taskId, String actorId, String reason) {
        try {
            apiClient.updateTaskStatus(taskId, actorId, "open", reason);
        }
        catch (RuntimeException updateFailure) {
            log.error("Unable to release crashed SDLC stage task {} back to open", taskId, updateFailure);
        }
    }

    private void block(String taskId, String actorId, String reason) {
        apiClient.updateTaskStatus(taskId, actorId, "blocked", reason);
    }
}
