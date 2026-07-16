package dev.nathan.sbaagentic.runner.ship;

import java.io.File;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nathan.sbaagentic.runner.BlackBoxApiClient;
import dev.nathan.sbaagentic.runner.RepoConfig;
import dev.nathan.sbaagentic.runner.RunnerNaming;
import dev.nathan.sbaagentic.runner.process.ProcessRunner;
import dev.nathan.sbaagentic.runner.process.ProcessRunner.ProcessResult;
import dev.nathan.sbaagentic.runner.process.TmuxController;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class ShipExecutor {

    private static final Logger log = LoggerFactory.getLogger(ShipExecutor.class);
    private static final Duration SHIP_TIMEOUT = Duration.ofMinutes(35);
    private static final Duration GIT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration DEFAULT_REPAIR_WAIT = Duration.ofMinutes(15);
    private static final Duration DEFAULT_REPAIR_POLL = Duration.ofSeconds(30);
    private static final int MAX_UNPARSEABLE_DETAIL = 500;

    private final BlackBoxApiClient apiClient;
    private final ProcessRunner processRunner;
    private final TmuxController tmux;
    private final ObjectMapper objectMapper;
    private final Duration repairWait;
    private final Duration repairPoll;

    @Autowired
    public ShipExecutor(
            BlackBoxApiClient apiClient,
            ProcessRunner processRunner,
            TmuxController tmux,
            ObjectMapper objectMapper) {
        this(
                apiClient,
                processRunner,
                tmux,
                objectMapper,
                DEFAULT_REPAIR_WAIT,
                DEFAULT_REPAIR_POLL);
    }

    ShipExecutor(
            BlackBoxApiClient apiClient,
            ProcessRunner processRunner,
            TmuxController tmux,
            ObjectMapper objectMapper,
            Duration repairWait,
            Duration repairPoll) {
        this.apiClient = apiClient;
        this.processRunner = processRunner;
        this.tmux = tmux;
        this.objectMapper = objectMapper;
        this.repairWait = requirePositive(repairWait, "repairWait");
        this.repairPoll = requirePositive(repairPoll, "repairPoll");
    }

    public ShipResult ship(
            String taskId,
            String actorId,
            RepoConfig repoConfig,
            String branch,
            File worktreeDir,
            String title,
            String summary,
            String tmuxSessionNameForRepair) {
        List<String> command = shipCommand(
                taskId, repoConfig, branch, worktreeDir, title, summary);
        ShipResult result = invokeShip(command, new File(repoConfig.path()));

        if ("blocked".equals(result.status())) {
            String originalCommit = currentCommit(worktreeDir);
            steerRepairBestEffort(tmuxSessionNameForRepair, result.reason());
            if (awaitNewCommit(worktreeDir, originalCommit)) {
                result = invokeShip(command, new File(repoConfig.path()));
            }
        }

        apiClient.annotate(
                taskId,
                actorId,
                "progress",
                describe(result),
                null);
        return result;
    }

    private ShipResult invokeShip(List<String> command, File repoDir) {
        ProcessResult processResult;
        try {
            processResult = processRunner.run(command, repoDir, SHIP_TIMEOUT);
        }
        catch (RuntimeException ex) {
            return localOnly("ship.sh failed to run: " + message(ex));
        }

        String json = lastNonBlankLine(processResult.stdout());
        if (json != null) {
            try {
                ShipResult parsed = objectMapper.readValue(json, ShipResult.class);
                if (isKnownStatus(parsed.status())) {
                    return parsed;
                }
            }
            catch (JsonProcessingException ex) {
                log.warn("Unable to parse ship.sh result: {}", ex.getOriginalMessage());
            }
        }
        return localOnly("ship.sh produced unparseable output: " + outputDetail(processResult));
    }

    private void steerRepairBestEffort(String tmuxSessionName, String reason) {
        try {
            if (tmuxSessionName == null || !tmux.hasSession(tmuxSessionName)) {
                log.warn("Worker tmux session {} is unavailable for ship repair", tmuxSessionName);
                return;
            }
            tmux.sendKeys(
                    tmuxSessionName,
                    "The PR checks failed:\n" + safe(reason)
                            + "\nPlease fix and I will re-run verify + push.");
        }
        catch (RuntimeException ex) {
            log.warn("Unable to steer worker tmux session {} for ship repair", tmuxSessionName, ex);
        }
    }

    private boolean awaitNewCommit(File worktreeDir, String originalCommit) {
        Instant deadline = Instant.now().plus(repairWait);
        while (Instant.now().isBefore(deadline)) {
            if (!sleepUntilNextPoll(deadline)) {
                return false;
            }
            String currentCommit = currentCommit(worktreeDir);
            if (!originalCommit.isBlank()
                    && !currentCommit.isBlank()
                    && !Objects.equals(currentCommit, originalCommit)) {
                return true;
            }
        }
        return false;
    }

    private boolean sleepUntilNextPoll(Instant deadline) {
        long remainingMillis = Math.max(1, Duration.between(Instant.now(), deadline).toMillis());
        long sleepMillis = Math.min(repairPoll.toMillis(), remainingMillis);
        try {
            Thread.sleep(Math.max(1, sleepMillis));
            return true;
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private String currentCommit(File worktreeDir) {
        try {
            ProcessResult result = processRunner.run(
                    List.of(
                            "git",
                            "-C",
                            worktreeDir.getAbsolutePath(),
                            "log",
                            "-1",
                            "--format=%H"),
                    worktreeDir,
                    GIT_TIMEOUT);
            if (!result.timedOut() && result.exitCode() == 0) {
                return safe(result.stdout()).strip();
            }
        }
        catch (RuntimeException ex) {
            log.warn("Unable to read current commit in {}", worktreeDir, ex);
        }
        return "";
    }

    private static List<String> shipCommand(
            String taskId,
            RepoConfig repoConfig,
            String branch,
            File worktreeDir,
            String title,
            String summary) {
        return List.of(
                RunnerNaming.scriptPath("scripts/runner/ship.sh"),
                safe(taskId),
                safe(repoConfig.path()),
                safe(branch),
                worktreeDir.getAbsolutePath(),
                String.valueOf(repoConfig.push()),
                String.valueOf(repoConfig.autoMerge()),
                safe(repoConfig.danger()),
                safe(title),
                safe(summary));
    }

    private static String describe(ShipResult result) {
        return "Ship result: status=" + result.status()
                + ", reason=" + display(result.reason())
                + ", prUrl=" + display(result.prUrl())
                + ", mergeStatus=" + display(result.mergeStatus()) + ".";
    }

    private static ShipResult localOnly(String reason) {
        return new ShipResult("local-only", reason, null, null, List.of());
    }

    private static String outputDetail(ProcessResult result) {
        String stdout = safe(result.stdout());
        String stderr = safe(result.stderr());
        String combined = stdout + (stdout.isBlank() || stderr.isBlank() ? "" : "\n") + stderr;
        if (combined.isBlank()) {
            combined = "<no output; exit " + result.exitCode()
                    + (result.timedOut() ? ", timed out" : "") + ">";
        }
        return combined.substring(0, Math.min(MAX_UNPARSEABLE_DETAIL, combined.length()));
    }

    private static String lastNonBlankLine(String output) {
        if (output == null || output.isBlank()) {
            return null;
        }
        String last = null;
        for (String line : output.lines().toList()) {
            if (!line.isBlank()) {
                last = line;
            }
        }
        return last;
    }

    private static boolean isKnownStatus(String status) {
        return "local-only".equals(status)
                || "pr-open".equals(status)
                || "merged".equals(status)
                || "blocked".equals(status);
    }

    private static Duration requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static String display(String value) {
        return value == null || value.isBlank() ? "none" : value;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String message(Throwable error) {
        return error.getMessage() == null || error.getMessage().isBlank()
                ? error.getClass().getSimpleName()
                : error.getMessage();
    }

    public record ShipResult(
            String status,
            String reason,
            String prUrl,
            String mergeStatus,
            List<String> manualCommands) {

        public ShipResult {
            manualCommands = manualCommands == null ? List.of() : List.copyOf(manualCommands);
        }
    }
}
