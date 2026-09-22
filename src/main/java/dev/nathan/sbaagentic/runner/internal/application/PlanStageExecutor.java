package dev.nathan.sbaagentic.runner.internal.application;

import dev.nathan.sbaagentic.runner.RunnerConfig;
import dev.nathan.sbaagentic.runner.RunnerNaming;
import dev.nathan.sbaagentic.runner.internal.application.RunContextLoader.StageContext;
import dev.nathan.sbaagentic.runner.internal.application.WorktreeManager.CreatedWorktree;
import dev.nathan.sbaagentic.runner.internal.application.WorktreeManager.GitState;
import dev.nathan.sbaagentic.runner.internal.client.blackbox.BlackBoxApiClient;
import dev.nathan.sbaagentic.runner.internal.client.blackbox.Task;
import dev.nathan.sbaagentic.runner.internal.client.blackbox.TaskChange;
import dev.nathan.sbaagentic.runner.run.GoalPromptBuilder;
import dev.nathan.sbaagentic.runner.run.RunStage;
import dev.nathan.sbaagentic.runner.run.WorkerRunExecutor;
import dev.nathan.sbaagentic.runner.run.WorkerRunExecutor.WorkerRunResult;
import java.io.File;
import java.util.List;
import java.util.Optional;
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

    public void execute(TaskChange claimedPlanTask, RunnerConfig config, String actorId, String orchestratorSessionId) {
        Task task = claimedPlanTask.snapshot().task();
        File repoDir = null;
        File worktreeDir = null;
        String branchName = null;
        String tmuxSessionName = null;
        boolean completed = false;
        CreatedWorktree createdWorktree = null;
        String checkpoint = "plan task claimed; worktree creation not verified";
        try {
            Optional<StageContext> loaded =
                    contextLoader.loadSdlcContext(claimedPlanTask, config, actorId, "SDLC plan");
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
            checkpoint = "plan worktree created; worker not started";
            createdWorktree = worktreeManager.createdWorktree(repoDir, worktreeDir, branchName);

            GitState before = worktreeManager.gitState(worktreeDir);
            String prompt =
                    goalPromptBuilder.buildPlan(task.id(), context.spec().body(), context.resolvedVerify());
            tmuxSessionName = RunnerNaming.tmuxSessionName(task.id());
            checkpoint = "plan worktree created; worker execution entered, outcome not yet known";
            WorkerRunResult result = workerRunExecutor.execute(
                    task, repoDir, worktreeDir, prompt, config, actorId, orchestratorSessionId, RunStage.PLAN);
            tmuxSessionName = result.tmuxSessionName();
            checkpoint = "plan worker returned " + result.outcome();
            switch (result.outcome()) {
                case NO_ENGINE -> {
                    block(task.id(), actorId, "No enabled engine configured");
                    String disposition = cleanup(createdWorktree, tmuxSessionName);
                    worktreeManager.reportRecovery(
                            task.id(),
                            actorId,
                            orchestratorSessionId,
                            worktreeDir,
                            branchName,
                            checkpoint,
                            disposition);
                    createdWorktree = null;
                    repoDir = null;
                    worktreeDir = null;
                    branchName = null;
                    tmuxSessionName = null;
                }
                case REQUEUED -> {
                    String disposition = cleanup(createdWorktree, tmuxSessionName);
                    worktreeManager.reportRecovery(
                            task.id(),
                            actorId,
                            orchestratorSessionId,
                            worktreeDir,
                            branchName,
                            checkpoint,
                            disposition);

                    return;
                }
                case TIMED_OUT, BLOCKED -> {
                    worktreeManager.reportRecovery(
                            task.id(),
                            actorId,
                            orchestratorSessionId,
                            worktreeDir,
                            branchName,
                            checkpoint,
                            "Worktree and branch retained for inspection.");
                    block(
                            task.id(),
                            actorId,
                            result.outcome() == WorkerRunExecutor.WorkerOutcome.TIMED_OUT
                                    ? "Run timed out after 45m. " + result.detail()
                                    : result.detail());
                }
                case DONE -> {
                    Optional<String> plan =
                            stateReader.latestWorkerAnnotationText(task.id(), "plan", result.startedAt());
                    if (plan.isEmpty()) {
                        worktreeManager.reportRecovery(
                                task.id(),
                                actorId,
                                orchestratorSessionId,
                                worktreeDir,
                                branchName,
                                checkpoint,
                                "Plan annotation missing; worktree retained for inspection.");
                        block(task.id(), actorId, "SDLC plan worker reported done without a plan annotation.");

                        return;
                    }
                    if (!before.equals(worktreeManager.gitState(worktreeDir))) {
                        worktreeManager.reportRecovery(
                                task.id(),
                                actorId,
                                orchestratorSessionId,
                                worktreeDir,
                                branchName,
                                checkpoint,
                                "Plan worker changed the worktree; output retained for inspection.");
                        block(
                                task.id(),
                                actorId,
                                "SDLC plan stage changed the worktree; plan stages must be read-only.");

                        return;
                    }
                    checkpoint = "plan annotation found and read-only worktree verified; completion not acknowledged";
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
        } catch (RuntimeException ex) {
            log.error("SDLC plan execution failed for task {}; releasing it back to open", task.id(), ex);
            String disposition = cleanup(createdWorktree, tmuxSessionName);
            String recovery = worktreeManager.reportRecovery(
                    task.id(), actorId, orchestratorSessionId, worktreeDir, branchName, checkpoint, disposition);
            releaseToOpenBestEffort(
                    task.id(), actorId, "SDLC plan execution crashed: " + ex.getMessage() + ". " + recovery);
        } finally {
            workerRunExecutor.killSessionBestEffort(tmuxSessionName);
            workerRunExecutor.finish(task.id(), RunnerNaming.tmuxSessionName(task.id()));
            if (completed) {
                String disposition = cleanup(createdWorktree, tmuxSessionName);
                worktreeManager.reportRecovery(
                        task.id(),
                        actorId,
                        orchestratorSessionId,
                        worktreeDir,
                        branchName,
                        "plan completion acknowledged",
                        disposition);
            }
        }
    }

    private String cleanup(CreatedWorktree createdWorktree, String tmuxSessionName) {
        if (!workerRunExecutor.stopSessionForCleanup(tmuxSessionName)) {

            return "Worktree and branch preserved: worker shutdown could not be confirmed.";
        }

        return worktreeManager.cleanupWorktreeAndBranch(createdWorktree);
    }

    private void releaseToOpenBestEffort(String taskId, String actorId, String reason) {
        try {
            apiClient.updateTaskStatus(taskId, actorId, "open", reason);
        } catch (RuntimeException updateFailure) {
            log.error("Unable to release crashed SDLC stage task {} back to open", taskId, updateFailure);
        }
    }

    private void block(String taskId, String actorId, String reason) {
        apiClient.updateTaskStatus(taskId, actorId, "blocked", reason);
    }
}
