package dev.nathan.sbaagentic.runner;

import dev.nathan.sbaagentic.runner.internal.client.blackbox.BlackBoxApiClient;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nathan.sbaagentic.runner.engine.Engine;
import dev.nathan.sbaagentic.runner.internal.application.WorktreeManager;
import dev.nathan.sbaagentic.runner.internal.application.SdlcStateReader;
import dev.nathan.sbaagentic.runner.internal.application.SdlcStateReader.BuildArtifact;
import dev.nathan.sbaagentic.runner.internal.application.RunContextLoader;
import dev.nathan.sbaagentic.runner.internal.application.PlanStageExecutor;
import dev.nathan.sbaagentic.runner.internal.application.ReviewStageExecutor;
import dev.nathan.sbaagentic.runner.gate.StoryFrontmatter;
import dev.nathan.sbaagentic.runner.gate.StoryFrontmatterParser;
import dev.nathan.sbaagentic.runner.process.ProcessRunner;
import dev.nathan.sbaagentic.runner.process.ProcessRunner.ProcessResult;
import dev.nathan.sbaagentic.runner.process.TmuxController;
import dev.nathan.sbaagentic.runner.run.ActiveRunRegistry;
import dev.nathan.sbaagentic.runner.run.CompletionDetector;
import dev.nathan.sbaagentic.runner.run.GoalPromptBuilder;
import dev.nathan.sbaagentic.runner.run.RunStage;
import dev.nathan.sbaagentic.runner.run.WorkerRunExecutor;
import dev.nathan.sbaagentic.runner.run.WorkerRunExecutor.WorkerOutcome;
import dev.nathan.sbaagentic.runner.run.WorkerRunExecutor.WorkerRunResult;
import dev.nathan.sbaagentic.runner.run.WorkerSessionIngest;
import dev.nathan.sbaagentic.runner.ship.ShipExecutor;
import dev.nathan.sbaagentic.runner.ship.ShipExecutor.ShipResult;
import dev.nathan.sbaagentic.runner.internal.client.blackbox.Task;
import dev.nathan.sbaagentic.runner.internal.client.blackbox.TaskChange;
import dev.nathan.sbaagentic.runner.internal.client.blackbox.TaskSpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Component
@Primary
public class RunExecutor implements AutoCycle {

    private static final Logger log = LoggerFactory.getLogger(RunExecutor.class);
    private static final String INVALID_STORY_REASON =
            "Auto task has no valid repo/verify in its story frontmatter; "
                    + "this should have been caught at the gate.";

    private final BlackBoxApiClient apiClient;
    private final ProcessRunner processRunner;
    private final GoalPromptBuilder goalPromptBuilder;
    private final StoryFrontmatterParser frontmatterParser;
    private final ShipExecutor shipExecutor;
    private final WorkerRunExecutor workerRunExecutor;
    private final SdlcTaskChainer taskChainer;
    private final WorktreeManager worktreeManager;
    private final SdlcStateReader sdlcStateReader;
    private final RunContextLoader runContextLoader;
    private final PlanStageExecutor planStageExecutor;
    private final ReviewStageExecutor reviewStageExecutor;

    @Autowired
    public RunExecutor(
            BlackBoxApiClient apiClient,
            TmuxController tmux,
            ProcessRunner processRunner,
            CompletionDetector completionDetector,
            WorkerSessionIngest workerSessionIngest,
            GoalPromptBuilder goalPromptBuilder,
            StoryFrontmatterParser frontmatterParser,
            List<Engine> engines,
            ActiveRunRegistry activeRunRegistry,
            ShipExecutor shipExecutor,
            SdlcTaskChainer taskChainer) {
        this.apiClient = apiClient;
        this.processRunner = processRunner;
        this.goalPromptBuilder = goalPromptBuilder;
        this.frontmatterParser = frontmatterParser;
        this.shipExecutor = shipExecutor;
        this.taskChainer = taskChainer;
        this.worktreeManager = new WorktreeManager(apiClient, processRunner);
        this.sdlcStateReader = new SdlcStateReader(apiClient);
        this.runContextLoader = new RunContextLoader(apiClient, frontmatterParser);
        this.workerRunExecutor = new WorkerRunExecutor(
                apiClient,
                tmux,
                processRunner,
                completionDetector,
                workerSessionIngest,
                engines,
                activeRunRegistry);
        this.planStageExecutor = new PlanStageExecutor(
                apiClient,
                workerRunExecutor,
                goalPromptBuilder,
                runContextLoader,
                worktreeManager,
                sdlcStateReader);
        this.reviewStageExecutor = new ReviewStageExecutor(
                apiClient,
                workerRunExecutor,
                goalPromptBuilder,
                runContextLoader,
                worktreeManager,
                sdlcStateReader);
    }

    public RunExecutor(
            BlackBoxApiClient apiClient,
            TmuxController tmux,
            ProcessRunner processRunner,
            CompletionDetector completionDetector,
            WorkerSessionIngest workerSessionIngest,
            GoalPromptBuilder goalPromptBuilder,
            StoryFrontmatterParser frontmatterParser,
            List<Engine> engines,
            ActiveRunRegistry activeRunRegistry,
            ShipExecutor shipExecutor) {
        this(
                apiClient,
                tmux,
                processRunner,
                completionDetector,
                workerSessionIngest,
                goalPromptBuilder,
                frontmatterParser,
                engines,
                activeRunRegistry,
                shipExecutor,
                new SdlcTaskChainer(apiClient));
    }

    public RunExecutor(
            BlackBoxApiClient apiClient,
            TmuxController tmux,
            ProcessRunner processRunner,
            CompletionDetector completionDetector,
            WorkerSessionIngest workerSessionIngest,
            GoalPromptBuilder goalPromptBuilder,
            StoryFrontmatterParser frontmatterParser,
            List<Engine> engines,
            ActiveRunRegistry activeRunRegistry) {
        this(
                apiClient,
                tmux,
                processRunner,
                completionDetector,
                workerSessionIngest,
                goalPromptBuilder,
                frontmatterParser,
                engines,
                activeRunRegistry,
                new ShipExecutor(apiClient, processRunner, tmux, new ObjectMapper()),
                new SdlcTaskChainer(apiClient));
    }

    @Override
    public void execute(
            TaskChange claimedAutoTask,
            RunnerConfig config,
            String actorId,
            String orchestratorSessionId) {
        Task task = claimedAutoTask.snapshot().task();
        File repoDir = null;
        File worktreeDir = null;
        String branchName = null;
        String tmuxSessionName = null;
        boolean preserveWorktree = false;
        try {
            TaskSpec spec = claimedAutoTask.snapshot().spec();
            if (spec == null) {
                spec = apiClient.getSpec(task.specId());
            }

            Optional<StoryFrontmatterParser.ParsedStory> parsed = frontmatterParser.parse(spec.body());
            if (parsed.isEmpty() || isBlank(parsed.orElseThrow().frontmatter().repo())) {
                block(task.id(), actorId, INVALID_STORY_REASON);
                return;
            }
            StoryFrontmatter frontmatter = parsed.orElseThrow().frontmatter();
            Optional<RepoConfig> matchingRepo = safeList(config.repos()).stream()
                    .filter(Objects::nonNull)
                    .filter(repo -> Objects.equals(repo.path(), frontmatter.repo()))
                    .findFirst();
            if (matchingRepo.isEmpty()) {
                block(
                        task.id(),
                        actorId,
                        "Auto task repo is no longer present in runner config: " + frontmatter.repo());
                return;
            }

            RepoConfig repoConfig = matchingRepo.orElseThrow();
            repoDir = new File(repoConfig.path());
            String resolvedVerify = resolveVerify(frontmatter.verify(), repoConfig, repoDir);
            if (isBlank(resolvedVerify)) {
                block(task.id(), actorId, INVALID_STORY_REASON);
                return;
            }

            boolean sdlc = "sdlc".equals(frontmatter.mode());
            if (sdlc) {
                if (!hasApprovedPlan(spec.id(), actorId)) {
                    block(
                            task.id(),
                            actorId,
                            "SDLC build requires a completed plan and an un-rejected plan approval.");
                    return;
                }
                if (resumeDeferredBuildIfPresent(task, spec, actorId, repoDir)) {
                    return;
                }
            }

            worktreeDir = new File(repoDir, RunnerNaming.worktreeDirName(task.id())).getAbsoluteFile();
            branchName = branchName(task.title(), task.id());
            tmuxSessionName = RunnerNaming.tmuxSessionName(task.id());

            File worktreeParent = worktreeDir.getParentFile();
            if (worktreeParent != null
                    && !worktreeParent.isDirectory()
                    && !worktreeParent.mkdirs()) {
                block(
                        task.id(),
                        actorId,
                        "Unable to create worktree parent directory: " + worktreeParent);
                return;
            }

            ProcessResult worktreeResult = createWorktree(repoDir, worktreeDir, branchName);
            if (worktreeResult.exitCode() != 0 || worktreeResult.timedOut()) {
                block(
                        task.id(),
                        actorId,
                        "Unable to create git worktree: " + processDetail(worktreeResult));
                return;
            }
            apiClient.annotate(
                    task.id(),
                    actorId,
                    "progress",
                    "Worktree created at " + worktreeDir.getAbsolutePath()
                            + " on branch " + branchName + ".",
                    null);

            String prompt = goalPromptBuilder.build(task.id(), spec.body(), resolvedVerify);
            WorkerRunResult result = workerRunExecutor.execute(
                    task,
                    repoDir,
                    worktreeDir,
                    prompt,
                    config,
                    actorId,
                    orchestratorSessionId,
                    RunStage.BUILD);
            tmuxSessionName = result.tmuxSessionName();
            if (result.outcome() == WorkerOutcome.NO_ENGINE) {
                cleanupWorktreeAndBranch(repoDir, worktreeDir, branchName, tmuxSessionName);
                block(task.id(), actorId, "No enabled engine configured");
                return;
            }
            if (result.outcome() == WorkerOutcome.REQUEUED) {
                cleanupWorktreeAndBranch(repoDir, worktreeDir, branchName, tmuxSessionName);
                return;
            }

            switch (result.outcome()) {
                case TIMED_OUT -> {
                    apiClient.updateTaskStatus(
                            task.id(),
                            actorId,
                            "blocked",
                            "Run timed out after 45m. " + result.detail());
                    workerRunExecutor.killSessionBestEffort(tmuxSessionName);
                }
                case BLOCKED -> {
                    apiClient.updateTaskStatus(task.id(), actorId, "blocked", result.detail());
                    workerRunExecutor.killSessionBestEffort(tmuxSessionName);
                }
                case DONE -> {
                    if (sdlc) {
                        apiClient.annotate(
                                task.id(),
                                actorId,
                                "progress",
                                "SDLC build verified and committed; shipping is deferred until review approval.",
                                Map.of(
                                        "branch", branchName,
                                        "worktree", worktreeDir.getAbsolutePath()));
                        preserveWorktree = true;
                        deferSdlcShip(
                                task,
                                spec,
                                actorId,
                                branchName,
                                worktreeDir,
                                tmuxSessionName,
                                result.detail());
                        return;
                    }
                    apiClient.annotate(
                            task.id(),
                            actorId,
                            "progress",
                            "Worker reported done; handing off to ship.",
                            null);
                    String workerSummary = isBlank(result.detail())
                            ? "Auto-lane run completed."
                            : result.detail();
                    ShipResult shipResult = shipExecutor.ship(
                            task.id(),
                            actorId,
                            repoConfig,
                            branchName,
                            worktreeDir,
                            task.title(),
                            workerSummary,
                            tmuxSessionName);
                    if ("blocked".equals(shipResult.status())) {
                        apiClient.updateTaskStatus(
                                task.id(),
                                actorId,
                                "blocked",
                                "Ship failed after one repair round: " + shipResult.reason());
                        workerRunExecutor.killSessionBestEffort(tmuxSessionName);
                        return;
                    }

                    String completionSummary;
                    List<String> openLoops;
                    String nextAction;
                    switch (shipResult.status()) {
                        case "merged" -> {
                            completionSummary = "Shipped and merged: " + shipResult.prUrl();
                            openLoops = List.of();
                            nextAction = "None — merged to default branch.";
                        }
                        case "pr-open" -> {
                            completionSummary = "PR opened, not auto-merged: " + shipResult.prUrl();
                            openLoops = List.of("Merge " + shipResult.prUrl() + " manually.");
                            nextAction = "Review and merge " + shipResult.prUrl() + ".";
                        }
                        case "local-only" -> {
                            completionSummary = "Work committed locally only: " + shipResult.reason();
                            openLoops = shipResult.manualCommands();
                            nextAction = "Run the manual commands to push/PR/merge, or re-run the runner "
                                    + "once the gate is fixed.";
                        }
                        default -> {
                            completionSummary = "Work committed locally only: unknown ship status "
                                    + shipResult.status();
                            openLoops = shipResult.manualCommands();
                            nextAction = "Inspect the ship annotation before retrying.";
                        }
                    }
                    apiClient.completeTask(
                            task.id(),
                            actorId,
                            "cli",
                            "blackbox-runner-run-" + task.id(),
                            completionSummary,
                            openLoops,
                            nextAction);
                    workerRunExecutor.killSessionBestEffort(tmuxSessionName);
                    if ("merged".equals(shipResult.status())) {
                        pruneMergedWorktree(task.id(), actorId, repoDir, worktreeDir);
                    }
                }
                case NO_ENGINE, REQUEUED -> throw new IllegalStateException(
                        "Worker run terminal handling reached unexpected outcome " + result.outcome());
            }
        }
        catch (RuntimeException ex) {
            log.error("Auto-lane execution failed for task {}; releasing it back to open", task.id(), ex);
            try {
                apiClient.updateTaskStatus(
                        task.id(),
                        actorId,
                        "open",
                        "Auto-lane execution crashed: " + ex.getMessage());
            }
            catch (RuntimeException updateFailure) {
                log.error("Unable to release crashed auto task {} back to open", task.id(), updateFailure);
            }
            if (!preserveWorktree) {
                cleanupWorktreeAndBranch(repoDir, worktreeDir, branchName, tmuxSessionName);
            }
        }
        finally {
            workerRunExecutor.finish(task.id(), RunnerNaming.tmuxSessionName(task.id()));
        }
    }

    private boolean resumeDeferredBuildIfPresent(
            Task task, TaskSpec spec, String actorId, File repoDir) {
        Optional<BuildArtifact> artifact = buildArtifact(task.id(), actorId);
        if (artifact.isEmpty()) {
            return false;
        }

        if (!validPreservedWorktree(repoDir, task, artifact.orElseThrow())
                || !hasWorkerDone(task.id())) {
            block(
                    task.id(),
                    actorId,
                    "Preserved SDLC build state failed validation; refusing to skip the build worker.");
            return true;
        }

        apiClient.completeTask(
                task.id(),
                actorId,
                "cli",
                "blackbox-runner-run-" + task.id(),
                "SDLC build completed and preserved on branch "
                        + artifact.orElseThrow().branch() + ".",
                List.of(),
                "SDLC review-lane execution will pick this up next.");
        taskChainer.ensureTask(
                spec.id(), task.title(), "sdlc:review", task.priority(), actorId);
        return true;
    }

    private void deferSdlcShip(
            Task task,
            TaskSpec spec,
            String actorId,
            String branchName,
            File worktreeDir,
            String tmuxSessionName,
            String workerSummary) {
        try {
            String summary = isBlank(workerSummary)
                    ? "SDLC build completed and preserved on branch " + branchName + "."
                    : workerSummary + " Preserved on branch " + branchName + " for SDLC review.";
            apiClient.completeTask(
                    task.id(),
                    actorId,
                    "cli",
                    "blackbox-runner-run-" + task.id(),
                    summary,
                    List.of(),
                    "SDLC review-lane execution will pick this up next.");
            taskChainer.ensureTask(
                    spec.id(), task.title(), "sdlc:review", task.priority(), actorId);
        }
        finally {
            workerRunExecutor.killSessionBestEffort(tmuxSessionName);
        }
    }

    private boolean hasApprovedPlan(String specId, String actorId) {
        return sdlcStateReader.hasApprovedPlan(specId, actorId);
    }

    private Optional<BuildArtifact> buildArtifact(String taskId, String actorId) {
        return sdlcStateReader.buildArtifact(taskId, actorId);
    }

    private boolean hasWorkerDone(String taskId) {
        return sdlcStateReader.hasWorkerDone(taskId);
    }

    public void executePlan(
            TaskChange claimedPlanTask,
            RunnerConfig config,
            String actorId,
            String orchestratorSessionId) {
        planStageExecutor.execute(claimedPlanTask, config, actorId, orchestratorSessionId);
    }

    public void executeReview(
            TaskChange claimedReviewTask,
            RunnerConfig config,
            String actorId,
            String orchestratorSessionId) {
        reviewStageExecutor.execute(claimedReviewTask, config, actorId, orchestratorSessionId);
    }

    private boolean validPreservedWorktree(
            File repoDir, Task buildTask, BuildArtifact artifact) {
        return artifact != null && worktreeManager.validPreservedWorktree(
                repoDir, buildTask, artifact.branch(), artifact.worktree());
    }

    ProcessResult createWorktree(File repoDir, File worktreeDir, String branchName) {
        return worktreeManager.createWorktree(repoDir, worktreeDir, branchName);
    }

    static String branchName(String title, String taskId) {
        return WorktreeManager.branchName(title, taskId);
    }

    private void pruneMergedWorktree(
            String taskId, String actorId, File repoDir, File worktreeDir) {
        worktreeManager.pruneMergedWorktree(taskId, actorId, repoDir, worktreeDir);
    }

    private void cleanupWorktreeAndBranch(
            File repoDir, File worktreeDir, String branchName, String tmuxSessionName) {
        if (tmuxSessionName != null) {
            workerRunExecutor.killSessionBestEffort(tmuxSessionName);
        }
        worktreeManager.cleanupWorktreeAndBranch(repoDir, worktreeDir, branchName);
    }

    private void block(String taskId, String actorId, String reason) {
        apiClient.updateTaskStatus(taskId, actorId, "blocked", reason);
    }

    private static String resolveVerify(String storyVerify, RepoConfig repoConfig, File repoDir) {
        return RunContextLoader.resolveVerify(storyVerify, repoConfig, repoDir);
    }

    private static String processDetail(ProcessResult result) {
        String output = !isBlank(result.stderr()) ? result.stderr().strip() : safeStrip(result.stdout());
        return "exit " + result.exitCode()
                + (result.timedOut() ? ", timed out" : "")
                + (output.isBlank() ? "" : ": " + output);
    }

    private static String safeStrip(String value) {
        return value == null ? "" : value.strip();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

}
