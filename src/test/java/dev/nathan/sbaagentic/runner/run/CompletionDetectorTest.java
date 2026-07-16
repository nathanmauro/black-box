package dev.nathan.sbaagentic.runner.run;

import java.io.File;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import dev.nathan.sbaagentic.runner.process.ProcessRunner;
import dev.nathan.sbaagentic.runner.process.TmuxController;
import dev.nathan.sbaagentic.task.TaskEvent;
import dev.nathan.sbaagentic.task.TaskEventType;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
class CompletionDetectorTest {

    private static final String TASK_ID = "12345678-abcd-4abc-8abc-1234567890ab";
    private static final String SESSION = "bb-run-12345678";

    @Test
    void returnsDoneForWorkerDoneAnnotationOnFirstPoll() {
        FakeBlackBoxApiClient apiClient = new FakeBlackBoxApiClient();
        apiClient.taskEvents = List.of(workerDone("done", "green"));

        CompletionDetector.CompletionResult result = detector(apiClient, new FakeTmux(true))
                .awaitCompletion(
                        TASK_ID,
                        SESSION,
                        new File("."),
                        Duration.ofSeconds(1),
                        Duration.ofMillis(10),
                        Instant.EPOCH);

        assertThat(result.outcome()).isEqualTo(CompletionDetector.Outcome.DONE);
        assertThat(result.detail()).isEqualTo("green");
    }

    @Test
    void returnsBlockedWithWorkerReportedText() {
        FakeBlackBoxApiClient apiClient = new FakeBlackBoxApiClient();
        apiClient.taskEvents = List.of(workerDone("blocked", "need human input"));

        CompletionDetector.CompletionResult result = detector(apiClient, new FakeTmux(true))
                .awaitCompletion(
                        TASK_ID,
                        SESSION,
                        new File("."),
                        Duration.ofSeconds(1),
                        Duration.ofMillis(10),
                        Instant.EPOCH);

        assertThat(result.outcome()).isEqualTo(CompletionDetector.Outcome.BLOCKED);
        assertThat(result.detail()).isEqualTo("need human input");
    }

    @Test
    void returnsBlockedImmediatelyWhenTmuxSessionDisappears() {
        FakeBlackBoxApiClient apiClient = new FakeBlackBoxApiClient();

        CompletionDetector.CompletionResult result = detector(apiClient, new FakeTmux(false))
                .awaitCompletion(
                        TASK_ID,
                        SESSION,
                        new File("."),
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(1),
                        Instant.EPOCH);

        assertThat(result.outcome()).isEqualTo(CompletionDetector.Outcome.BLOCKED);
        assertThat(result.detail()).isEqualTo("tmux session ended without a completion report");
    }

    @Test
    void timesOutWithPaneAndCommitProbeDetail() {
        FakeBlackBoxApiClient apiClient = new FakeBlackBoxApiClient();
        FakeTmux tmux = new FakeTmux(true);
        tmux.pane = "Working (still waiting)";

        CompletionDetector.CompletionResult result = detector(apiClient, tmux)
                .awaitCompletion(
                        TASK_ID,
                        SESSION,
                        new File("."),
                        Duration.ofMillis(200),
                        Duration.ofMillis(50),
                        Instant.EPOCH);

        assertThat(result.outcome()).isEqualTo(CompletionDetector.Outcome.TIMED_OUT);
        assertThat(result.detail()).contains(
                "pane still contains an active/waiting marker",
                "git log -1: abc123 test commit",
                "Working (still waiting)");
    }

    @Test
    void ignoresWorkerDoneAnnotationsBeforeRunStart() {
        FakeBlackBoxApiClient apiClient = new FakeBlackBoxApiClient();
        Instant since = Instant.now();
        TaskEvent stale = new TaskEvent(
                "event-stale",
                TASK_ID,
                TaskEventType.NOTE,
                "blackbox-runner-worker",
                null,
                null,
                Map.of(
                        "kind", "progress",
                        "text", "stale completion",
                        "dataJson", Map.of("event", "worker_done", "outcome", "done")),
                since.minusSeconds(1));
        apiClient.taskEvents = List.of(stale);

        CompletionDetector.CompletionResult staleResult = detector(apiClient, new FakeTmux(true))
                .awaitCompletion(
                        TASK_ID,
                        SESSION,
                        new File("."),
                        Duration.ofMillis(50),
                        Duration.ofMillis(10),
                        since);

        assertThat(staleResult.outcome()).isEqualTo(CompletionDetector.Outcome.TIMED_OUT);

        apiClient.taskEvents = List.of(stale, workerDone("done", "fresh completion"));
        CompletionDetector.CompletionResult freshResult = detector(apiClient, new FakeTmux(true))
                .awaitCompletion(
                        TASK_ID,
                        SESSION,
                        new File("."),
                        Duration.ofSeconds(1),
                        Duration.ofMillis(10),
                        since);

        assertThat(freshResult.outcome()).isEqualTo(CompletionDetector.Outcome.DONE);
        assertThat(freshResult.detail()).isEqualTo("fresh completion");
    }

    private static CompletionDetector detector(FakeBlackBoxApiClient apiClient, FakeTmux tmux) {
        ProcessRunner processRunner = (command, workingDir, timeout) ->
                new ProcessRunner.ProcessResult(0, "abc123 test commit\n", "", false);
        return new CompletionDetector(apiClient, tmux, processRunner);
    }

    private static TaskEvent workerDone(String outcome, String text) {
        return new TaskEvent(
                "event-1",
                TASK_ID,
                TaskEventType.NOTE,
                "blackbox-runner-worker",
                null,
                null,
                Map.of(
                        "kind", "progress",
                        "text", text,
                        "dataJson", Map.of("event", "worker_done", "outcome", outcome)),
                Instant.now());
    }

    private static final class FakeTmux implements TmuxController {

        private boolean sessionExists;
        private String pane = "";

        private FakeTmux(boolean sessionExists) {
            this.sessionExists = sessionExists;
        }

        @Override
        public boolean hasSession(String sessionName) {
            return sessionExists;
        }

        @Override
        public void killSession(String sessionName) {
            sessionExists = false;
        }

        @Override
        public void newSession(String sessionName, File cwd, int width, int height) {
            sessionExists = true;
        }

        @Override
        public void sendKeys(String sessionName, String text) {
        }

        @Override
        public String capturePane(String sessionName) {
            return pane;
        }
    }
}
