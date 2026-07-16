package dev.nathan.sbaagentic.runner;

import java.io.File;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nathan.sbaagentic.runner.engine.Engine;
import dev.nathan.sbaagentic.runner.engine.RateLimitDetector;
import dev.nathan.sbaagentic.runner.gate.StoryFrontmatter;
import dev.nathan.sbaagentic.runner.gate.StoryFrontmatterParser;
import dev.nathan.sbaagentic.runner.process.ProcessRunner;
import dev.nathan.sbaagentic.runner.process.ProcessRunner.ProcessResult;
import dev.nathan.sbaagentic.runner.process.TmuxController;
import dev.nathan.sbaagentic.runner.run.ActiveRunRegistry;
import dev.nathan.sbaagentic.runner.run.CompletionDetector;
import dev.nathan.sbaagentic.runner.run.CompletionDetector.CompletionResult;
import dev.nathan.sbaagentic.runner.run.GoalPromptBuilder;
import dev.nathan.sbaagentic.runner.run.WorkerSessionIngest;
import dev.nathan.sbaagentic.runner.ship.ShipExecutor;
import dev.nathan.sbaagentic.runner.ship.ShipExecutor.ShipResult;
import dev.nathan.sbaagentic.task.Task;
import dev.nathan.sbaagentic.task.TaskChange;
import dev.nathan.sbaagentic.task.TaskSpec;

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
    private static final Duration RUN_TIMEOUT = Duration.ofMinutes(45);
    private static final Duration COMPLETION_POLL_INTERVAL = Duration.ofSeconds(15);
    private static final Duration COMPLETION_SLICE_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration NOTIFY_TIMEOUT = Duration.ofSeconds(10);
    private static final int MAX_RATE_LIMIT_PANE_LENGTH = 2_000;
    private static final String INVALID_STORY_REASON =
            "Auto task has no valid repo/verify in its story frontmatter; "
                    + "this should have been caught at the gate.";

    private final BlackBoxApiClient apiClient;
    private final TmuxController tmux;
    private final ProcessRunner processRunner;
    private final CompletionDetector completionDetector;
    private final WorkerSessionIngest workerSessionIngest;
    private final GoalPromptBuilder goalPromptBuilder;
    private final StoryFrontmatterParser frontmatterParser;
    private final List<Engine> engines;
    private final ActiveRunRegistry activeRunRegistry;
    private final ShipExecutor shipExecutor;

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
            ShipExecutor shipExecutor) {
        this.apiClient = apiClient;
        this.tmux = tmux;
        this.processRunner = processRunner;
        this.completionDetector = completionDetector;
        this.workerSessionIngest = workerSessionIngest;
        this.goalPromptBuilder = goalPromptBuilder;
        this.frontmatterParser = frontmatterParser;
        this.engines = engines;
        this.activeRunRegistry = activeRunRegistry;
        this.shipExecutor = shipExecutor;
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
                new ShipExecutor(apiClient, processRunner, tmux, new ObjectMapper()));
    }

    @Override
    public void execute(
            TaskChange claimedAutoTask,
            RunnerConfig config,
            String actorId,
            String orchestratorSessionId) {
        Task task = claimedAutoTask.snapshot().task();
        String registeredTmuxSession = null;
        File repoDir = null;
        File worktreeDir = null;
        String branchName = null;
        String tmuxSessionName = null;
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

            if (tmux.hasSession(tmuxSessionName)) {
                tmux.killSession(tmuxSessionName);
            }
            tmux.newSession(tmuxSessionName, worktreeDir, 220, 50);

            Optional<SelectedEngine> selected = selectEngine(config);
            if (selected.isEmpty()) {
                cleanupWorktreeAndBranch(repoDir, worktreeDir, branchName, tmuxSessionName);
                block(task.id(), actorId, "No enabled engine configured");
                return;
            }

            SelectedEngine selection = selected.orElseThrow();
            String prompt = goalPromptBuilder.build(task.id(), spec.body(), resolvedVerify);
            Instant runStart = Instant.now();
            Instant runDeadline = runStart.plus(RUN_TIMEOUT);
            Instant lastIncrementalIngestAt = runStart;
            launchEngine(selection, prompt, task.id(), worktreeDir, tmuxSessionName);
            activeRunRegistry.register(task.id(), tmuxSessionName);
            registeredTmuxSession = tmuxSessionName;
            apiClient.annotate(
                    task.id(),
                    actorId,
                    "progress",
                    "Engine '" + selection.engine().id()
                            + "' launched in tmux session " + tmuxSessionName + ".",
                    null);

            CompletionResult result = null;
            while (Instant.now().isBefore(runDeadline)) {
                Duration remaining = Duration.between(Instant.now(), runDeadline);
                Duration slice = remaining.compareTo(COMPLETION_SLICE_TIMEOUT) < 0
                        ? remaining
                        : COMPLETION_SLICE_TIMEOUT;
                if (slice.isZero() || slice.isNegative()) {
                    break;
                }
                Duration pollInterval = slice.compareTo(COMPLETION_POLL_INTERVAL) < 0
                        ? slice
                        : COMPLETION_POLL_INTERVAL;
                result = completionDetector.awaitCompletion(
                        task.id(),
                        tmuxSessionName,
                        worktreeDir,
                        slice,
                        pollInterval,
                        runStart);
                if (result.outcome() != CompletionDetector.Outcome.TIMED_OUT) {
                    break;
                }

                if (Duration.between(lastIncrementalIngestAt, Instant.now())
                                .compareTo(Duration.ofSeconds(60))
                        >= 0) {
                    try {
                        workerSessionIngest.ingestIncremental(
                                worktreeDir, task.id(), actorId, orchestratorSessionId);
                    }
                    catch (RuntimeException ex) {
                        log.warn("Periodic worker session ingest failed for task {}", task.id(), ex);
                    }
                    lastIncrementalIngestAt = Instant.now();
                }

                Optional<String> rateLimitedPane = rateLimitedPane(tmuxSessionName);
                if (rateLimitedPane.isEmpty()) {
                    continue;
                }

                String paneSnippet = rateLimitedPane.orElseThrow();
                String limitedEngineId = selection.engine().id();
                apiClient.annotate(
                        task.id(),
                        actorId,
                        "engine",
                        "Engine '" + limitedEngineId + "' hit a rate or usage limit. Last pane:\n"
                                + paneSnippet,
                        null);
                apiClient.annotate(
                        task.id(),
                        actorId,
                        "progress",
                        "Engine '" + limitedEngineId + "' was rate-limited; checking for a fallback.",
                        null);
                notifyBestEffort(
                        config,
                        repoDir,
                        "Black Box task " + task.id() + " engine " + limitedEngineId
                                + " was rate-limited.");

                Optional<SelectedEngine> fallback = nextEngine(config, selection);
                if (fallback.isEmpty()) {
                    String reason = "Engine rate-limited and no fallback engine is configured/enabled.";
                    apiClient.updateTaskStatus(task.id(), actorId, "open", reason);
                    annotateBestEffort(task.id(), actorId, reason);
                    notifyBestEffort(
                            config,
                            repoDir,
                            "Black Box task " + task.id()
                                    + " is open again because no fallback engine is configured.");
                    cleanupWorktreeAndBranch(
                            repoDir, worktreeDir, branchName, tmuxSessionName);
                    return;
                }

                selection = fallback.orElseThrow();
                killSessionBestEffort(tmuxSessionName);
                tmux.newSession(tmuxSessionName, worktreeDir, 220, 50);
                launchEngine(selection, prompt, task.id(), worktreeDir, tmuxSessionName);
                apiClient.annotate(
                        task.id(),
                        actorId,
                        "progress",
                        "Relaunched with fallback engine '" + selection.engine().id()
                                + "' in tmux session " + tmuxSessionName + ".",
                        null);
            }
            if (result == null || (result.outcome() == CompletionDetector.Outcome.TIMED_OUT
                    && !Instant.now().isBefore(runDeadline))) {
                String detail = result == null
                        ? "Run deadline elapsed before a completion result was available."
                        : result.detail();
                result = new CompletionResult(CompletionDetector.Outcome.TIMED_OUT, detail);
            }
            try {
                workerSessionIngest.ingestAndLink(
                        worktreeDir, task.id(), actorId, orchestratorSessionId);
            }
            catch (RuntimeException ex) {
                log.warn("Worker session ingest failed for task {}", task.id(), ex);
            }

            switch (result.outcome()) {
                case TIMED_OUT -> {
                    apiClient.updateTaskStatus(
                            task.id(),
                            actorId,
                            "blocked",
                            "Run timed out after 45m. " + result.detail());
                    killSessionBestEffort(tmuxSessionName);
                }
                case BLOCKED -> {
                    apiClient.updateTaskStatus(task.id(), actorId, "blocked", result.detail());
                    killSessionBestEffort(tmuxSessionName);
                }
                case DONE -> {
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
                        killSessionBestEffort(tmuxSessionName);
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
                    killSessionBestEffort(tmuxSessionName);
                    if ("merged".equals(shipResult.status())) {
                        pruneMergedWorktree(task.id(), actorId, repoDir, worktreeDir);
                    }
                }
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
            cleanupWorktreeAndBranch(repoDir, worktreeDir, branchName, tmuxSessionName);
        }
        finally {
            if (registeredTmuxSession != null) {
                activeRunRegistry.deregister(task.id(), registeredTmuxSession);
            }
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

    static String shQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private void launchEngine(
            SelectedEngine selection,
            String prompt,
            String taskId,
            File worktreeDir,
            String tmuxSessionName) {
        List<String> command = selection.engine().command(prompt, selection.config());
        if (command == null || command.isEmpty()) {
            throw new IllegalStateException(
                    "Engine '" + selection.engine().id() + "' returned no command");
        }
        String commandLine = shellCommand(command);
        if ("fake".equals(selection.engine().id())) {
            String baseUrl = System.getenv("SBA_BASE_URL");
            String baseUrlExport = "";
            if (!isBlank(baseUrl)) {
                baseUrlExport = "; export SBA_BASE_URL=" + shQuote(baseUrl);
            }
            commandLine = "export SBA_TASK_ID=" + shQuote(taskId)
                    + "; export SBA_WORKTREE=" + shQuote(worktreeDir.getAbsolutePath())
                    + baseUrlExport
                    + "; " + commandLine;
        }
        tmux.sendKeys(tmuxSessionName, commandLine);
    }

    private Optional<String> rateLimitedPane(String tmuxSessionName) {
        try {
            if (!tmux.hasSession(tmuxSessionName)) {
                return Optional.empty();
            }
            String pane = tmux.capturePane(tmuxSessionName);
            if (!RateLimitDetector.matches(pane)) {
                return Optional.empty();
            }
            if (pane.length() <= MAX_RATE_LIMIT_PANE_LENGTH) {
                return Optional.of(pane);
            }
            return Optional.of(
                    "[truncated]\n" + pane.substring(pane.length() - MAX_RATE_LIMIT_PANE_LENGTH));
        }
        catch (RuntimeException ex) {
            log.warn("Unable to inspect tmux session {} for rate limits", tmuxSessionName, ex);
            return Optional.empty();
        }
    }

    private Optional<SelectedEngine> selectEngine(RunnerConfig config) {
        List<EngineConfig> configuredEngines = safeList(config.engines());
        for (int index = 0; index < configuredEngines.size(); index++) {
            Optional<SelectedEngine> selected = selectedEngine(configuredEngines, index);
            if (selected.isPresent()) {
                return selected;
            }
        }
        return Optional.empty();
    }

    private Optional<SelectedEngine> nextEngine(
            RunnerConfig config, SelectedEngine currentSelection) {
        List<EngineConfig> configuredEngines = safeList(config.engines());
        for (int index = currentSelection.configIndex() + 1;
                index < configuredEngines.size();
                index++) {
            Optional<SelectedEngine> selected = selectedEngine(configuredEngines, index);
            if (selected.isPresent()) {
                return selected;
            }
        }
        return Optional.empty();
    }

    private Optional<SelectedEngine> selectedEngine(
            List<EngineConfig> configuredEngines, int index) {
        EngineConfig engineConfig = configuredEngines.get(index);
        if (engineConfig == null || !engineConfig.enabled()) {
            return Optional.empty();
        }
        return safeList(engines).stream()
                .filter(Objects::nonNull)
                .filter(candidate -> Objects.equals(candidate.id(), engineConfig.id()))
                .findFirst()
                .map(engine -> new SelectedEngine(engine, engineConfig, index));
    }

    private void notifyBestEffort(RunnerConfig config, File workingDir, String message) {
        if (config == null || isBlank(config.notifyCommand())) {
            return;
        }
        String command = config.notifyCommand().replace("{msg}", message);
        try {
            ProcessResult result = processRunner.run(
                    List.of("/bin/sh", "-c", command), workingDir, NOTIFY_TIMEOUT);
            if (result.timedOut() || result.exitCode() != 0) {
                log.warn("Runner notify command failed: {}", processDetail(result));
            }
        }
        catch (RuntimeException ex) {
            log.warn("Runner notify command failed", ex);
        }
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
            killSessionBestEffort(tmuxSessionName);
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

    private void killSessionBestEffort(String tmuxSessionName) {
        try {
            if (tmux.hasSession(tmuxSessionName)) {
                tmux.killSession(tmuxSessionName);
            }
        }
        catch (RuntimeException ex) {
            log.warn("Unable to kill tmux session {}", tmuxSessionName, ex);
        }
    }

    private void block(String taskId, String actorId, String reason) {
        apiClient.updateTaskStatus(taskId, actorId, "blocked", reason);
    }

    private static String shellCommand(List<String> command) {
        return String.join(" ", command.stream().map(RunExecutor::shQuote).toList());
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

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    private record SelectedEngine(Engine engine, EngineConfig config, int configIndex) {
    }
}
