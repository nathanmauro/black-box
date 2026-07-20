package dev.nathan.sbaagentic.runner.internal.application;

import java.io.File;
import java.util.List;
import java.util.Optional;

import dev.nathan.sbaagentic.runner.RunnerConfig;
import dev.nathan.sbaagentic.runner.RunnerNaming;
import dev.nathan.sbaagentic.runner.internal.application.RunContextLoader.StageContext;
import dev.nathan.sbaagentic.runner.internal.application.WorktreeManager.GitState;
import dev.nathan.sbaagentic.runner.internal.client.blackbox.BlackBoxApiClient;
import dev.nathan.sbaagentic.runner.internal.client.blackbox.Task;
import dev.nathan.sbaagentic.runner.internal.client.blackbox.TaskChange;
import dev.nathan.sbaagentic.runner.run.GoalPromptBuilder;
import dev.nathan.sbaagentic.runner.run.RunStage;
import dev.nathan.sbaagentic.runner.run.WorkerRunExecutor;
import dev.nathan.sbaagentic.runner.run.WorkerRunExecutor.WorkerRunResult;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PlanStageExecutor {

    private static final Logger log = LoggerFactory.getLogger(PlanStageExecutor.class);

    private final BlackBoxApiClient apiClient;
    private final WorkerRunExecutor workerRunExecutor;
    private final GoalPromptBuilder goalPromptBuilder;
    private final RunContextLoader contextLoader;
    private final WorktreeManager worktreeManager;
    private final SdlcStateReader stateReader;

    public PlanStageExecutor(
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
            TaskChange claimedPlanTask,
            RunnerConfig config,
            String actorId,
            String orchestratorSessionId) {
        Task task = claimedPlanTask.snapshot().task();
        File repoDir = null;
        File worktreeDir = null;
        String branchName = null;
        String tmuxSessionName = null;
        boolean completed = false;
        try {
            Optional<StageContext> loaded = contextLoader.loadSdlcContext(
                    claimedPlanTask, config, actorId, "SDLC plan");
            if (loaded.isEmpty()) {
                return;
            }
            StageContext context = loaded.orElseThrow();
            repoDir = context.repoDir();
            worktreeDir = new File(repoDir, RunnerNaming.worktreeDirName(task.id())).getAbsoluteFile();
            branchName = WorktreeManager.branchName(task.title(), task.id());
            if (!worktreeManager.prepareWorktree(task, actorId, repoDir, worktreeDir, branchName)) {
                return;
            }

            GitState before = worktreeManager.gitState(worktreeDir);
            String prompt = goalPromptBuilder.buildPlan(
                    task.id(), context.spec().body(), context.resolvedVerify());
            WorkerRunResult result = workerRunExecutor.execute(
                    task,
                    repoDir,
                    worktreeDir,
                    prompt,
                    config,
                    actorId,
                    orchestratorSessionId,
                    RunStage.PLAN);
            tmuxSessionName = result.tmuxSessionName();
            switch (result.outcome()) {
                case NO_ENGINE -> {
                    block(task.id(), actorId, "No enabled engine configured");
                    cleanup(repoDir, worktreeDir, branchName, tmuxSessionName);
                    repoDir = null;
                    worktreeDir = null;
                    branchName = null;
                    tmuxSessionName = null;
                }
                case REQUEUED -> {
                    cleanup(repoDir, worktreeDir, branchName, tmuxSessionName);
                    return;
                }
                case TIMED_OUT -> block(task.id(), actorId, "Run timed out after 45m. " + result.detail());
                case BLOCKED -> block(task.id(), actorId, result.detail());
                case DONE -> {
                    Optional<String> plan = stateReader.latestWorkerAnnotationText(
                            task.id(), "plan", result.startedAt());
                    if (plan.isEmpty()) {
                        block(task.id(), actorId,
                                "SDLC plan worker reported done without a plan annotation.");
                        return;
                    }
                    if (!before.equals(worktreeManager.gitState(worktreeDir))) {
                        block(task.id(), actorId,
                                "SDLC plan stage changed the worktree; plan stages must be read-only.");
                        return;
                    }
                    apiClient.completeTask(
                            task.id(),
                            actorId,
                            "cli",
                            "blackbox-runner-plan-" + task.id(),
                            plan.orElseThrow(),
                            List.of(),
                            "Await human approval of the SDLC plan.");
                    completed = true;
                }
            }
        }
        catch (RuntimeException ex) {
            log.error("SDLC plan execution failed for task {}; releasing it back to open", task.id(), ex);
            releaseToOpenBestEffort(task.id(), actorId, "SDLC plan execution crashed: " + ex.getMessage());
            cleanup(repoDir, worktreeDir, branchName, tmuxSessionName);
        }
        finally {
            workerRunExecutor.killSessionBestEffort(tmuxSessionName);
            workerRunExecutor.finish(task.id(), RunnerNaming.tmuxSessionName(task.id()));
            if (completed) {
                worktreeManager.cleanupWorktreeAndBranch(repoDir, worktreeDir, branchName);
            }
        }
    }

    private void cleanup(File repoDir, File worktreeDir, String branchName, String tmuxSessionName) {
        if (tmuxSessionName != null) {
            workerRunExecutor.killSessionBestEffort(tmuxSessionName);
        }
        worktreeManager.cleanupWorktreeAndBranch(repoDir, worktreeDir, branchName);
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
