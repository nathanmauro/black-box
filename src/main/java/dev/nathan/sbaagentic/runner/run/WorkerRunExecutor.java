package dev.nathan.sbaagentic.runner.run;

import java.io.File;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import dev.nathan.sbaagentic.runner.BlackBoxApiClient;
import dev.nathan.sbaagentic.runner.EngineConfig;
import dev.nathan.sbaagentic.runner.RunnerConfig;
import dev.nathan.sbaagentic.runner.RunnerNaming;
import dev.nathan.sbaagentic.runner.engine.Engine;
import dev.nathan.sbaagentic.runner.engine.RateLimitDetector;
import dev.nathan.sbaagentic.runner.process.ProcessRunner;
import dev.nathan.sbaagentic.runner.process.ProcessRunner.ProcessResult;
import dev.nathan.sbaagentic.runner.process.TmuxController;
import dev.nathan.sbaagentic.runner.run.CompletionDetector.CompletionResult;
import dev.nathan.sbaagentic.task.Task;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Shared tmux, engine, fallback, completion, and ingest machinery for runner worker stages. */
public final class WorkerRunExecutor {

    private static final Logger log = LoggerFactory.getLogger(WorkerRunExecutor.class);
    private static final Duration RUN_TIMEOUT = Duration.ofMinutes(45);
    private static final Duration COMPLETION_POLL_INTERVAL = Duration.ofSeconds(15);
    private static final Duration COMPLETION_SLICE_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration NOTIFY_TIMEOUT = Duration.ofSeconds(10);
    private static final int MAX_RATE_LIMIT_PANE_LENGTH = 2_000;

    private final BlackBoxApiClient apiClient;
    private final TmuxController tmux;
    private final ProcessRunner processRunner;
    private final CompletionDetector completionDetector;
    private final WorkerSessionIngest workerSessionIngest;
    private final List<Engine> engines;
    private final ActiveRunRegistry activeRunRegistry;

    public WorkerRunExecutor(
            BlackBoxApiClient apiClient,
            TmuxController tmux,
            ProcessRunner processRunner,
            CompletionDetector completionDetector,
            WorkerSessionIngest workerSessionIngest,
            List<Engine> engines,
            ActiveRunRegistry activeRunRegistry) {
        this.apiClient = apiClient;
        this.tmux = tmux;
        this.processRunner = processRunner;
        this.completionDetector = completionDetector;
        this.workerSessionIngest = workerSessionIngest;
        this.engines = engines;
        this.activeRunRegistry = activeRunRegistry;
    }

    public WorkerRunResult execute(
            Task task,
            File repoDir,
            File worktreeDir,
            String prompt,
            RunnerConfig config,
            String actorId,
            String orchestratorSessionId,
            RunStage stage) {
        String tmuxSessionName = RunnerNaming.tmuxSessionName(task.id());
        if (tmux.hasSession(tmuxSessionName)) {
            tmux.killSession(tmuxSessionName);
        }
        tmux.newSession(tmuxSessionName, worktreeDir, 220, 50);

        Optional<SelectedEngine> selected = selectEngine(config);
        if (selected.isEmpty()) {
            return new WorkerRunResult(
                    WorkerOutcome.NO_ENGINE,
                    "No enabled engine configured",
                    tmuxSessionName,
                    null);
        }

        SelectedEngine selection = selected.orElseThrow();
        Instant runStart = Instant.now();
        Instant runDeadline = runStart.plus(RUN_TIMEOUT);
        Instant lastIncrementalIngestAt = runStart;
        launchEngine(selection, prompt, task.id(), worktreeDir, tmuxSessionName, stage);
        activeRunRegistry.register(task.id(), tmuxSessionName);
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
                return new WorkerRunResult(
                        WorkerOutcome.REQUEUED,
                        reason,
                        tmuxSessionName,
                        runStart);
            }

            selection = fallback.orElseThrow();
            killSessionBestEffort(tmuxSessionName);
            tmux.newSession(tmuxSessionName, worktreeDir, 220, 50);
            launchEngine(selection, prompt, task.id(), worktreeDir, tmuxSessionName, stage);
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

        return new WorkerRunResult(
                switch (result.outcome()) {
                    case DONE -> WorkerOutcome.DONE;
                    case BLOCKED -> WorkerOutcome.BLOCKED;
                    case TIMED_OUT -> WorkerOutcome.TIMED_OUT;
                },
                result.detail(),
                tmuxSessionName,
                runStart);
    }

    public void finish(String taskId, String tmuxSessionName) {
        activeRunRegistry.deregister(taskId, tmuxSessionName);
    }

    public void killSessionBestEffort(String tmuxSessionName) {
        try {
            if (tmuxSessionName != null && tmux.hasSession(tmuxSessionName)) {
                tmux.killSession(tmuxSessionName);
            }
        }
        catch (RuntimeException ex) {
            log.warn("Unable to kill tmux session {}", tmuxSessionName, ex);
        }
    }

    private void launchEngine(
            SelectedEngine selection,
            String prompt,
            String taskId,
            File worktreeDir,
            String tmuxSessionName,
            RunStage stage) {
        List<String> command = selection.engine().command(prompt, selection.config());
        if (command == null || command.isEmpty()) {
            throw new IllegalStateException(
                    "Engine '" + selection.engine().id() + "' returned no command");
        }
        String commandLine = "export SBA_STAGE=" + shQuote(stage.environmentValue())
                // Always pin the worker's report target to this runner's resolved base URL.
                // The tmux server's global environment can carry a stale SBA_BASE_URL from an
                // unrelated harness run, silently pointing report.sh at a dead server.
                + "; export SBA_BASE_URL=" + shQuote(apiClient.baseUrl());
        if ("fake".equals(selection.engine().id())) {
            commandLine += "; export SBA_TASK_ID=" + shQuote(taskId)
                    + "; export SBA_WORKTREE=" + shQuote(worktreeDir.getAbsolutePath());
        }
        commandLine += "; " + shellCommand(command);
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
            if (selected.isPresent() && !"fake".equals(selected.orElseThrow().engine().id())) {
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

    private void annotateBestEffort(String taskId, String actorId, String text) {
        try {
            apiClient.annotate(taskId, actorId, "progress", text, null);
        }
        catch (RuntimeException ex) {
            log.warn("Unable to annotate runner state for task {}", taskId, ex);
        }
    }

    private static String shellCommand(List<String> command) {
        return String.join(" ", command.stream().map(WorkerRunExecutor::shQuote).toList());
    }

    private static String shQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
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

    public record WorkerRunResult(
            WorkerOutcome outcome,
            String detail,
            String tmuxSessionName,
            Instant startedAt) {
    }

    public enum WorkerOutcome {
        DONE,
        BLOCKED,
        TIMED_OUT,
        NO_ENGINE,
        REQUEUED
    }

    private record SelectedEngine(Engine engine, EngineConfig config, int configIndex) {
    }
}
