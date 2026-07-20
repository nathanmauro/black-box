package dev.nathan.sbaagentic.runner.run;

import java.io.File;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import dev.nathan.sbaagentic.runner.internal.client.blackbox.BlackBoxApiClient;
import dev.nathan.sbaagentic.runner.process.ProcessRunner;
import dev.nathan.sbaagentic.runner.process.ProcessRunner.ProcessResult;
import dev.nathan.sbaagentic.runner.process.TmuxController;
import dev.nathan.sbaagentic.runner.internal.client.blackbox.TaskEvent;
import dev.nathan.sbaagentic.runner.internal.client.blackbox.TaskEventType;

import org.springframework.stereotype.Component;

@Component
public class CompletionDetector {

    private static final int MAX_PANE_DETAIL_LENGTH = 2_000;

    private final BlackBoxApiClient apiClient;
    private final TmuxController tmux;
    private final ProcessRunner processRunner;

    public CompletionDetector(
            BlackBoxApiClient apiClient,
            TmuxController tmux,
            ProcessRunner processRunner) {
        this.apiClient = apiClient;
        this.tmux = tmux;
        this.processRunner = processRunner;
    }

    public CompletionResult awaitCompletion(
            String taskId,
            String tmuxSessionName,
            File worktreeDir,
            Duration timeout,
            Duration pollInterval,
            Instant since) {
        Instant deadline = Instant.now().plus(requirePositive(timeout, "timeout"));
        Duration interval = requirePositive(pollInterval, "pollInterval");

        while (Instant.now().isBefore(deadline)) {
            Optional<CompletionResult> reported = reportedCompletion(taskId, since);
            if (reported.isPresent()) {
                return reported.orElseThrow();
            }
            if (!tmux.hasSession(tmuxSessionName)) {
                return new CompletionResult(
                        Outcome.BLOCKED, "tmux session ended without a completion report");
            }
            if (!sleepUntilNextPoll(interval, deadline)) {
                return new CompletionResult(
                        Outcome.BLOCKED, "Interrupted while waiting for worker completion");
            }
        }

        // Close the polling race before declaring the bounded run timed out.
        Optional<CompletionResult> lastChance = reportedCompletion(taskId, since);
        if (lastChance.isPresent()) {
            return lastChance.orElseThrow();
        }
        return timeoutResult(tmuxSessionName, worktreeDir);
    }

    private Optional<CompletionResult> reportedCompletion(String taskId, Instant since) {
        List<TaskEvent> events = apiClient.taskEvents(taskId);
        if (events == null) {
            return Optional.empty();
        }
        for (int index = events.size() - 1; index >= 0; index--) {
            TaskEvent event = events.get(index);
            if (event == null
                    || event.observedAt() == null
                    || event.observedAt().isBefore(since)
                    || event.type() != TaskEventType.NOTE
                    || event.detail() == null) {
                continue;
            }
            Map<String, Object> detail = event.detail();
            if (!"progress".equals(detail.get("kind"))) {
                continue;
            }
            Object dataValue = detail.get("dataJson");
            if (!(dataValue instanceof Map<?, ?> data)
                    || !"worker_done".equals(data.get("event"))) {
                continue;
            }
            String text = detail.get("text") instanceof String value ? value : "Worker reported blocked";
            if ("done".equals(data.get("outcome"))) {
                return Optional.of(new CompletionResult(Outcome.DONE, text));
            }
            if ("blocked".equals(data.get("outcome"))) {
                return Optional.of(new CompletionResult(Outcome.BLOCKED, text));
            }
        }
        return Optional.empty();
    }

    private CompletionResult timeoutResult(String tmuxSessionName, File worktreeDir) {
        String paneText;
        try {
            paneText = tmux.hasSession(tmuxSessionName)
                    ? tmux.capturePane(tmuxSessionName)
                    : "<tmux session no longer exists>";
        }
        catch (RuntimeException ex) {
            paneText = "<unable to capture pane: " + message(ex) + ">";
        }
        paneText = truncatePane(paneText);

        String commitEvidence;
        try {
            ProcessResult result = processRunner.run(
                    List.of(
                            "git",
                            "-C",
                            worktreeDir.getAbsolutePath(),
                            "log",
                            "-1",
                            "--oneline"),
                    worktreeDir,
                    Duration.ofSeconds(10));
            if (!result.timedOut() && result.exitCode() == 0 && !result.stdout().isBlank()) {
                commitEvidence = "git log -1: " + result.stdout().strip();
            }
            else {
                String error = result.timedOut()
                        ? "timed out"
                        : "exit " + result.exitCode() + ": " + firstNonBlank(result.stderr(), result.stdout());
                commitEvidence = "no commit evidence (" + error + ")";
            }
        }
        catch (RuntimeException ex) {
            commitEvidence = "commit probe failed: " + message(ex);
        }

        String activity = paneIndicatesActive(paneText)
                ? "pane still contains an active/waiting marker"
                : "pane contains no recognized active marker";
        return new CompletionResult(
                Outcome.TIMED_OUT,
                activity + "; " + commitEvidence + "; last pane:\n" + paneText);
    }

    /**
     * A pane marker is only evidence that the worker is still active. Its absence never proves
     * completion; only the worker_done annotation does that.
     */
    static boolean paneIndicatesActive(String paneText) {
        if (paneText == null || paneText.isBlank()) {
            return false;
        }
        String normalized = paneText.toLowerCase(Locale.ROOT);
        if (paneText.contains("Working (")
                || normalized.contains("approve")
                || normalized.contains("trust")
                || normalized.contains("background terminal")
                || normalized.contains("waiting for terminal")) {
            return true;
        }
        String tail = normalized.substring(Math.max(0, normalized.length() - 120)).stripTrailing();
        return tail.contains("?");
    }

    private static boolean sleepUntilNextPoll(Duration pollInterval, Instant deadline) {
        long remainingMillis = Math.max(1, Duration.between(Instant.now(), deadline).toMillis());
        long sleepMillis = Math.min(pollInterval.toMillis(), remainingMillis);
        try {
            Thread.sleep(Math.max(1, sleepMillis));
            return true;
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static Duration requirePositive(Duration duration, String name) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return duration;
    }

    private static String truncatePane(String paneText) {
        if (paneText == null) {
            return "";
        }
        if (paneText.length() <= MAX_PANE_DETAIL_LENGTH) {
            return paneText;
        }
        return "[truncated]\n" + paneText.substring(paneText.length() - MAX_PANE_DETAIL_LENGTH);
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first.strip();
        }
        return second == null ? "" : second.strip();
    }

    private static String message(Throwable error) {
        return error.getMessage() == null || error.getMessage().isBlank()
                ? error.getClass().getSimpleName()
                : error.getMessage();
    }

    public record CompletionResult(Outcome outcome, String detail) {
    }

    public enum Outcome {
        DONE,
        BLOCKED,
        TIMED_OUT
    }
}
