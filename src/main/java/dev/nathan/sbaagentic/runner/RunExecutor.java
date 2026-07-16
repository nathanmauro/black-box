package dev.nathan.sbaagentic.runner;

import java.io.File;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nathan.sbaagentic.runner.engine.Engine;
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
import dev.nathan.sbaagentic.task.Task;
import dev.nathan.sbaagentic.task.TaskChange;
import dev.nathan.sbaagentic.task.TaskEvent;
import dev.nathan.sbaagentic.task.TaskEventType;
import dev.nathan.sbaagentic.task.TaskSpec;
import dev.nathan.sbaagentic.task.TaskSnapshot;
import dev.nathan.sbaagentic.task.TaskStatus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Component
@Primary
public class RunExecutor implements AutoCycle {

    private static final Logger log = LoggerFactory.getLogger(RunExecutor.class);
    private static final Duration GIT_TIMEOUT = Duration.ofSeconds(30);
    private static final String WORKER_ACTOR = "blackbox-runner-worker";
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
        this.workerRunExecutor = new WorkerRunExecutor(
                apiClient,
                tmux,
                processRunner,
                completionDetector,
                workerSessionIngest,
                engines,
                activeRunRegistry);
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
        Optional<Task> planTask = latestTask(specId, "sdlc:plan", TaskStatus.DONE);
        if (planTask.isEmpty()) {
            return false;
        }
        List<TaskEvent> events = safeList(apiClient.taskEvents(planTask.orElseThrow().id()));
        boolean hasPlan = events.stream().anyMatch(event -> event != null
                && event.type() == TaskEventType.NOTE
                && WORKER_ACTOR.equals(event.actor())
                && event.detail() != null
                && "plan".equals(event.detail().get("kind"))
                && !isBlank(stringValue(event.detail().get("text"))));
        if (!hasPlan) {
            return false;
        }
        boolean rejected = events.stream().anyMatch(event -> {
            Map<?, ?> data = dataJson(event);
            boolean approvalRejected = isAnnotation(event, "approval")
                    && "plan".equals(stringValue(data.get("stage")))
                    && "reject".equals(stringValue(data.get("decision")));
            boolean rejectionRecorded = event != null
                    && Objects.equals(actorId, event.actor())
                    && isAnnotation(event, "progress")
                    && "rejection_recorded".equals(stringValue(data.get("sdlc")));
            return approvalRejected || rejectionRecorded;
        });
        if (rejected) {
            return false;
        }
        for (int index = events.size() - 1; index >= 0; index--) {
            TaskEvent event = events.get(index);
            if (!isAnnotation(event, "approval")) {
                continue;
            }
            Map<?, ?> data = dataJson(event);
            return "plan".equals(stringValue(data.get("stage")))
                    && "approve".equals(stringValue(data.get("decision")));
        }
        return false;
    }

    private Optional<BuildArtifact> buildArtifact(String taskId, String actorId) {
        List<TaskEvent> events = safeList(apiClient.taskEvents(taskId));
        for (int index = events.size() - 1; index >= 0; index--) {
            TaskEvent event = events.get(index);
            if (event == null
                    || event.type() != TaskEventType.NOTE
                    || event.detail() == null
                    || !Objects.equals(actorId, event.actor())
                    || !"progress".equals(event.detail().get("kind"))) {
                continue;
            }
            Object dataValue = event.detail().get("dataJson");
            if (!(dataValue instanceof Map<?, ?> data)) {
                continue;
            }
            String branch = stringValue(data.get("branch"));
            String worktree = stringValue(data.get("worktree"));
            if (!isBlank(branch) && !isBlank(worktree) && Path.of(worktree).isAbsolute()) {
                return Optional.of(new BuildArtifact(branch, new File(worktree).getAbsoluteFile()));
            }
        }
        return Optional.empty();
    }

    private boolean hasWorkerDone(String taskId) {
        return safeList(apiClient.taskEvents(taskId)).stream().anyMatch(event -> {
            if (event == null
                    || event.type() != TaskEventType.NOTE
                    || !WORKER_ACTOR.equals(event.actor())
                    || event.detail() == null
                    || !"progress".equals(event.detail().get("kind"))) {
                return false;
            }
            Map<?, ?> data = dataJson(event);
            return "worker_done".equals(stringValue(data.get("event")))
                    && "done".equals(stringValue(data.get("outcome")));
        });
    }

    public void executePlan(
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
            Optional<StageContext> loaded = loadSdlcContext(
                    claimedPlanTask, config, actorId, "SDLC plan");
            if (loaded.isEmpty()) {
                return;
            }
            StageContext context = loaded.orElseThrow();
            repoDir = context.repoDir();
            worktreeDir = new File(repoDir, RunnerNaming.worktreeDirName(task.id())).getAbsoluteFile();
            branchName = branchName(task.title(), task.id());
            if (!prepareWorktree(task, actorId, repoDir, worktreeDir, branchName)) {
                return;
            }

            GitState before = gitState(worktreeDir);
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
                    cleanupWorktreeAndBranch(
                            repoDir, worktreeDir, branchName, tmuxSessionName);
                    repoDir = null;
                    worktreeDir = null;
                    branchName = null;
                    tmuxSessionName = null;
                }
                case REQUEUED -> {
                    cleanupWorktreeAndBranch(repoDir, worktreeDir, branchName, tmuxSessionName);
                    return;
                }
                case TIMED_OUT -> block(
                        task.id(), actorId, "Run timed out after 45m. " + result.detail());
                case BLOCKED -> block(task.id(), actorId, result.detail());
                case DONE -> {
                    Optional<String> plan = latestWorkerAnnotationText(
                            task.id(), "plan", result.startedAt());
                    if (plan.isEmpty()) {
                        block(
                                task.id(),
                                actorId,
                                "SDLC plan worker reported done without a plan annotation.");
                        return;
                    }
                    if (!before.equals(gitState(worktreeDir))) {
                        block(
                                task.id(),
                                actorId,
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
            cleanupWorktreeAndBranch(
                    repoDir, worktreeDir, branchName, tmuxSessionName);
        }
        finally {
            workerRunExecutor.killSessionBestEffort(tmuxSessionName);
            workerRunExecutor.finish(task.id(), RunnerNaming.tmuxSessionName(task.id()));
            if (completed) {
                cleanupWorktreeAndBranch(repoDir, worktreeDir, branchName, null);
            }
        }
    }

    public void executeReview(
            TaskChange claimedReviewTask,
            RunnerConfig config,
            String actorId,
            String orchestratorSessionId) {
        Task task = claimedReviewTask.snapshot().task();
        String tmuxSessionName = null;
        try {
            Optional<StageContext> loaded = loadSdlcContext(
                    claimedReviewTask, config, actorId, "SDLC review");
            if (loaded.isEmpty()) {
                return;
            }
            StageContext context = loaded.orElseThrow();
            Optional<Task> buildTask = latestTask(context.spec().id(), "auto", TaskStatus.DONE);
            if (buildTask.isEmpty()) {
                block(task.id(), actorId, "SDLC review has no completed build task for its spec.");
                return;
            }
            Task build = buildTask.orElseThrow();
            Optional<BuildArtifact> artifact = buildArtifact(build.id(), actorId);
            if (artifact.isEmpty()
                    || !validPreservedWorktree(context.repoDir(), build, artifact.orElse(null))) {
                block(
                        task.id(),
                        actorId,
                        "SDLC review build artifact is missing or outside the configured repo worktrees.");
                return;
            }
            if (!hasApprovedPlan(context.spec().id(), actorId)) {
                block(
                        task.id(),
                        actorId,
                        "SDLC review requires an approved, un-rejected plan for its spec.");
                return;
            }
            Optional<Task> planTask = latestTask(
                    context.spec().id(), "sdlc:plan", TaskStatus.DONE);
            Optional<String> approvedPlan = planTask.flatMap(plan -> latestWorkerAnnotationText(
                    plan.id(), "plan", null));
            if (approvedPlan.isEmpty()) {
                block(task.id(), actorId, "SDLC review has no approved plan annotation for its spec.");
                return;
            }

            BuildArtifact buildArtifact = artifact.orElseThrow();
            GitState before = gitState(buildArtifact.worktree());
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
                case TIMED_OUT -> block(
                        task.id(), actorId, "Run timed out after 45m. " + result.detail());
                case BLOCKED -> block(task.id(), actorId, result.detail());
                case DONE -> {
                    Optional<String> review = latestWorkerAnnotationText(
                            task.id(), "review", result.startedAt());
                    if (review.isEmpty()) {
                        block(
                                task.id(),
                                actorId,
                                "SDLC review worker reported done without a review annotation.");
                        return;
                    }
                    if (!before.equals(gitState(buildArtifact.worktree()))) {
                        block(
                                task.id(),
                                actorId,
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
            releaseToOpenBestEffort(
                    task.id(), actorId, "SDLC review execution crashed: " + ex.getMessage());
        }
        finally {
            workerRunExecutor.killSessionBestEffort(tmuxSessionName);
            workerRunExecutor.finish(task.id(), RunnerNaming.tmuxSessionName(task.id()));
        }
    }

    private Optional<StageContext> loadSdlcContext(
            TaskChange claimedTask,
            RunnerConfig config,
            String actorId,
            String stageLabel) {
        Task task = claimedTask.snapshot().task();
        TaskSpec spec = claimedTask.snapshot().spec();
        if (spec == null) {
            spec = apiClient.getSpec(task.specId());
        }
        Optional<StoryFrontmatterParser.ParsedStory> parsed = frontmatterParser.parse(spec.body());
        if (parsed.isEmpty()
                || !"sdlc".equals(parsed.orElseThrow().frontmatter().mode())
                || isBlank(parsed.orElseThrow().frontmatter().repo())) {
            block(task.id(), actorId, stageLabel + " task has invalid SDLC story frontmatter.");
            return Optional.empty();
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
                    stageLabel + " repo is no longer present in runner config: " + frontmatter.repo());
            return Optional.empty();
        }
        RepoConfig repoConfig = matchingRepo.orElseThrow();
        File repoDir = new File(repoConfig.path()).getAbsoluteFile();
        String resolvedVerify = resolveVerify(frontmatter.verify(), repoConfig, repoDir);
        if (isBlank(resolvedVerify)) {
            block(task.id(), actorId, stageLabel + " task has no resolvable verify command.");
            return Optional.empty();
        }
        return Optional.of(new StageContext(spec, repoConfig, repoDir, resolvedVerify));
    }

    private boolean prepareWorktree(
            Task task,
            String actorId,
            File repoDir,
            File worktreeDir,
            String branchName) {
        File worktreeParent = worktreeDir.getParentFile();
        if (worktreeParent != null
                && !worktreeParent.isDirectory()
                && !worktreeParent.mkdirs()) {
            block(
                    task.id(),
                    actorId,
                    "Unable to create worktree parent directory: " + worktreeParent);
            return false;
        }
        ProcessResult worktreeResult = createWorktree(repoDir, worktreeDir, branchName);
        if (worktreeResult.exitCode() != 0 || worktreeResult.timedOut()) {
            block(
                    task.id(),
                    actorId,
                    "Unable to create git worktree: " + processDetail(worktreeResult));
            return false;
        }
        apiClient.annotate(
                task.id(),
                actorId,
                "progress",
                "Worktree created at " + worktreeDir.getAbsolutePath()
                        + " on branch " + branchName + ".",
                null);
        return true;
    }

    private Optional<Task> latestTask(String specId, String lane, TaskStatus status) {
        return safeList(apiClient.listTasks(null, lane)).stream()
                .map(TaskSnapshot::task)
                .filter(Objects::nonNull)
                .filter(task -> specId.equals(task.specId()))
                .filter(task -> lane.equals(task.lane()))
                .filter(task -> status == null || task.status() == status)
                .max(Comparator.comparing(
                        task -> task.updatedAt() == null ? task.createdAt() : task.updatedAt(),
                        Comparator.nullsFirst(Comparator.naturalOrder())));
    }

    private Optional<String> latestWorkerAnnotationText(
            String taskId, String kind, Instant since) {
        List<TaskEvent> events = safeList(apiClient.taskEvents(taskId));
        for (int index = events.size() - 1; index >= 0; index--) {
            TaskEvent event = events.get(index);
            if (event == null
                    || event.type() != TaskEventType.NOTE
                    || event.detail() == null
                    || !WORKER_ACTOR.equals(event.actor())
                    || event.observedAt() == null
                    || (since != null && event.observedAt().isBefore(since))
                    || !kind.equals(event.detail().get("kind"))) {
                continue;
            }
            String text = stringValue(event.detail().get("text"));
            if (!isBlank(text)) {
                return Optional.of(text);
            }
        }
        return Optional.empty();
    }

    private boolean validPreservedWorktree(
            File repoDir, Task buildTask, BuildArtifact artifact) {
        if (artifact == null
                || !artifact.worktree().isDirectory()
                || !branchName(buildTask.title(), buildTask.id()).equals(artifact.branch())) {
            return false;
        }
        Path expected = new File(repoDir, RunnerNaming.worktreeDirName(buildTask.id()))
                .toPath()
                .toAbsolutePath()
                .normalize();
        Path actual = artifact.worktree().toPath().toAbsolutePath().normalize();
        if (!expected.equals(actual)) {
            return false;
        }
        ProcessResult branch = processRunner.run(
                List.of(
                        "git",
                        "-C",
                        artifact.worktree().getAbsolutePath(),
                        "branch",
                        "--show-current"),
                artifact.worktree(),
                GIT_TIMEOUT);
        if (branch.timedOut()
                || branch.exitCode() != 0
                || !artifact.branch().equals(safeStrip(branch.stdout()))) {
            return false;
        }
        ProcessResult status = processRunner.run(
                List.of(
                        "git",
                        "-C",
                        artifact.worktree().getAbsolutePath(),
                        "status",
                        "--porcelain"),
                artifact.worktree(),
                GIT_TIMEOUT);
        return !status.timedOut()
                && status.exitCode() == 0
                && safeStrip(status.stdout()).isBlank();
    }

    private GitState gitState(File worktreeDir) {
        ProcessResult head = processRunner.run(
                List.of("git", "-C", worktreeDir.getAbsolutePath(), "rev-parse", "HEAD"),
                worktreeDir,
                GIT_TIMEOUT);
        ProcessResult status = processRunner.run(
                List.of("git", "-C", worktreeDir.getAbsolutePath(), "status", "--porcelain"),
                worktreeDir,
                GIT_TIMEOUT);
        if (head.timedOut()
                || head.exitCode() != 0
                || status.timedOut()
                || status.exitCode() != 0) {
            throw new IllegalStateException(
                    "Unable to verify stage worktree state: head=" + processDetail(head)
                            + ", status=" + processDetail(status));
        }
        return new GitState(safeStrip(head.stdout()), status.stdout() == null ? "" : status.stdout());
    }

    private void releaseToOpenBestEffort(String taskId, String actorId, String reason) {
        try {
            apiClient.updateTaskStatus(taskId, actorId, "open", reason);
        }
        catch (RuntimeException updateFailure) {
            log.error("Unable to release crashed SDLC stage task {} back to open", taskId, updateFailure);
        }
    }

    ProcessResult createWorktree(File repoDir, File worktreeDir, String branchName) {
        ProcessResult defaultBranchResult = processRunner.run(
                List.of(
                        "git",
                        "-C",
                        repoDir.getAbsolutePath(),
                        "rev-parse",
                        "--abbrev-ref",
                        "origin/HEAD"),
                repoDir,
                GIT_TIMEOUT);
        String defaultBranch;
        if (!defaultBranchResult.timedOut()
                && defaultBranchResult.exitCode() == 0
                && !defaultBranchResult.stdout().isBlank()) {
            defaultBranch = defaultBranchResult.stdout().strip();
            if (defaultBranch.startsWith("origin/")) {
                defaultBranch = defaultBranch.substring("origin/".length());
            }
        }
        else {
            ProcessResult localBranchResult = processRunner.run(
                    List.of(
                            "git",
                            "-C",
                            repoDir.getAbsolutePath(),
                            "symbolic-ref",
                            "--short",
                            "HEAD"),
                    repoDir,
                    GIT_TIMEOUT);
            if (localBranchResult.timedOut()
                    || localBranchResult.exitCode() != 0
                    || localBranchResult.stdout().isBlank()) {
                return localBranchResult;
            }
            defaultBranch = localBranchResult.stdout().strip();
        }
        return processRunner.run(
                List.of(
                        "git",
                        "-C",
                        repoDir.getAbsolutePath(),
                        "worktree",
                        "add",
                        worktreeDir.getAbsolutePath(),
                        "-b",
                        branchName,
                        defaultBranch),
                repoDir,
                GIT_TIMEOUT);
    }

    static String branchName(String title, String taskId) {
        String slug = title == null
                ? ""
                : title.toLowerCase(Locale.ROOT)
                        .replaceAll("[^a-z0-9]+", "-")
                        .replaceAll("^-+|-+$", "");
        if (slug.length() > 40) {
            slug = slug.substring(0, 40).replaceAll("-+$", "");
        }
        if (slug.isBlank()) {
            slug = "task";
        }
        return "auto/" + slug + "-" + RunnerNaming.taskShort(taskId);
    }

    private void pruneMergedWorktree(
            String taskId, String actorId, File repoDir, File worktreeDir) {
        ProcessResult status = processRunner.run(
                List.of(
                        "git",
                        "-C",
                        worktreeDir.getAbsolutePath(),
                        "status",
                        "--porcelain"),
                worktreeDir,
                GIT_TIMEOUT);
        if (status.timedOut() || status.exitCode() != 0) {
            annotateBestEffort(
                    taskId,
                    actorId,
                    "Merged PR, but left the worktree in place because cleanliness could not be "
                            + "verified: " + processDetail(status));
            return;
        }
        if (!status.stdout().isBlank()) {
            annotateBestEffort(
                    taskId,
                    actorId,
                    "Merged PR, but left the dirty worktree in place: "
                            + worktreeDir.getAbsolutePath());
            return;
        }

        ProcessResult remove = processRunner.run(
                List.of(
                        "git",
                        "-C",
                        repoDir.getAbsolutePath(),
                        "worktree",
                        "remove",
                        worktreeDir.getAbsolutePath(),
                        "--force"),
                repoDir,
                GIT_TIMEOUT);
        if (remove.timedOut() || remove.exitCode() != 0) {
            annotateBestEffort(
                    taskId,
                    actorId,
                    "Merged PR, but failed to remove the clean worktree: " + processDetail(remove));
            return;
        }

        ProcessResult prune = processRunner.run(
                List.of(
                        "git",
                        "-C",
                        repoDir.getAbsolutePath(),
                        "worktree",
                        "prune"),
                repoDir,
                GIT_TIMEOUT);
        if (prune.timedOut() || prune.exitCode() != 0) {
            annotateBestEffort(
                    taskId,
                    actorId,
                    "Merged worktree was removed, but git worktree prune failed: "
                            + processDetail(prune));
            return;
        }
        annotateBestEffort(
                taskId,
                actorId,
                "Merged worktree removed and pruned: " + worktreeDir.getAbsolutePath());
    }

    private void annotateBestEffort(String taskId, String actorId, String text) {
        try {
            apiClient.annotate(taskId, actorId, "progress", text, null);
        }
        catch (RuntimeException ex) {
            log.warn("Unable to annotate post-merge worktree state for task {}", taskId, ex);
        }
    }

    private void cleanupWorktreeAndBranch(
            File repoDir, File worktreeDir, String branchName, String tmuxSessionName) {
        if (tmuxSessionName != null) {
            workerRunExecutor.killSessionBestEffort(tmuxSessionName);
        }
        if (repoDir == null || worktreeDir == null) {
            return;
        }
        ProcessResult remove = processRunner.run(
                List.of(
                        "git",
                        "-C",
                        repoDir.getAbsolutePath(),
                        "worktree",
                        "remove",
                        worktreeDir.getAbsolutePath(),
                        "--force"),
                repoDir,
                GIT_TIMEOUT);
        if (remove.exitCode() != 0 || remove.timedOut()) {
            log.warn("Unable to remove worktree {} during cleanup: {}", worktreeDir, processDetail(remove));
        }
        if (branchName != null && !branchName.isBlank()) {
            ProcessResult deleteBranch = processRunner.run(
                    List.of("git", "-C", repoDir.getAbsolutePath(), "branch", "-D", branchName),
                    repoDir,
                    GIT_TIMEOUT);
            if (deleteBranch.exitCode() != 0 || deleteBranch.timedOut()) {
                log.warn(
                        "Unable to delete branch {} during cleanup: {}",
                        branchName,
                        processDetail(deleteBranch));
            }
        }
    }

    private void block(String taskId, String actorId, String reason) {
        apiClient.updateTaskStatus(taskId, actorId, "blocked", reason);
    }

    private static String resolveVerify(String storyVerify, RepoConfig repoConfig, File repoDir) {
        if (!isBlank(storyVerify)) {
            return storyVerify;
        }
        if (!isBlank(repoConfig.verify())) {
            return repoConfig.verify();
        }
        if (new File(repoDir, "pom.xml").isFile()) {
            return "mvn test";
        }
        if (new File(repoDir, "package.json").isFile()) {
            return "npm test";
        }
        if (new File(repoDir, "Makefile").isFile()) {
            return "make test";
        }
        return null;
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

    private static String stringValue(Object value) {
        return value instanceof String text ? text : null;
    }

    private static boolean isAnnotation(TaskEvent event, String kind) {
        return event != null
                && event.type() == TaskEventType.NOTE
                && event.detail() != null
                && kind.equals(stringValue(event.detail().get("kind")));
    }

    private static Map<?, ?> dataJson(TaskEvent event) {
        if (event == null || event.detail() == null) {
            return Map.of();
        }
        Object data = event.detail().get("dataJson");
        return data instanceof Map<?, ?> map ? map : Map.of();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    private record StageContext(
            TaskSpec spec,
            RepoConfig repoConfig,
            File repoDir,
            String resolvedVerify) {
    }

    private record BuildArtifact(String branch, File worktree) {
    }

    private record GitState(String head, String status) {
    }
}
