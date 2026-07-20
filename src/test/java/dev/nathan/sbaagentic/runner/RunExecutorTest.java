package dev.nathan.sbaagentic.runner;

import java.io.File;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import dev.nathan.sbaagentic.runner.engine.Engine;
import dev.nathan.sbaagentic.runner.engine.FakeEngine;
import dev.nathan.sbaagentic.runner.gate.StoryFrontmatterParser;
import dev.nathan.sbaagentic.runner.process.ProcessRunner;
import dev.nathan.sbaagentic.runner.process.ProcessRunner.ProcessResult;
import dev.nathan.sbaagentic.runner.process.TmuxController;
import dev.nathan.sbaagentic.runner.run.ActiveRunRegistry;
import dev.nathan.sbaagentic.runner.run.CompletionDetector;
import dev.nathan.sbaagentic.runner.run.CompletionDetector.CompletionResult;
import dev.nathan.sbaagentic.runner.run.CompletionDetector.Outcome;
import dev.nathan.sbaagentic.runner.run.GoalPromptBuilder;
import dev.nathan.sbaagentic.runner.run.WorkerSessionIngest;
import dev.nathan.sbaagentic.runner.ship.ShipExecutor;
import dev.nathan.sbaagentic.workflow.SpecStatus;
import dev.nathan.sbaagentic.workflow.Task;
import dev.nathan.sbaagentic.workflow.TaskChange;
import dev.nathan.sbaagentic.workflow.TaskSnapshot;
import dev.nathan.sbaagentic.workflow.TaskSpec;
import dev.nathan.sbaagentic.workflow.TaskStatus;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RunExecutorTest {

    private static final String TASK_ID = "12345678-abcd-4abc-8abc-1234567890ab";
    private static final String ACTOR_ID = "blackbox-runner";

    @TempDir
    Path tempDir;

    @Mock
    BlackBoxApiClient apiClient;

    @Mock
    TmuxController tmux;

    @Mock
    ProcessRunner processRunner;

    @Mock
    CompletionDetector completionDetector;

    @Mock
    WorkerSessionIngest workerSessionIngest;

    @Mock
    ShipExecutor shipExecutor;

    @Test
    void rateLimitedCodexDoesNotFallBackToEnabledFakeEngine() {
        when(apiClient.baseUrl()).thenReturn("http://127.0.0.1:8766");
        AtomicBoolean sessionExists = new AtomicBoolean();
        when(tmux.hasSession(anyString())).thenAnswer(invocation -> sessionExists.get());
        doAnswer(invocation -> {
            sessionExists.set(true);
            return null;
        }).when(tmux).newSession(anyString(), any(File.class), anyInt(), anyInt());
        doAnswer(invocation -> {
            sessionExists.set(false);
            return null;
        }).when(tmux).killSession(anyString());
        when(tmux.capturePane(anyString())).thenReturn("HTTP 429: Too Many Requests");
        when(completionDetector.awaitCompletion(
                anyString(),
                anyString(),
                any(File.class),
                any(Duration.class),
                any(Duration.class),
                any(Instant.class)))
                .thenReturn(new CompletionResult(Outcome.TIMED_OUT, "still running"));
        when(processRunner.run(anyList(), any(File.class), any(Duration.class)))
                .thenAnswer(invocation -> processResult(invocation.getArgument(0)));

        Engine codex = engine("codex", "codex-worker");
        ActiveRunRegistry activeRunRegistry = new ActiveRunRegistry();
        RunExecutor executor = new RunExecutor(
                apiClient,
                tmux,
                processRunner,
                completionDetector,
                workerSessionIngest,
                new GoalPromptBuilder(),
                new StoryFrontmatterParser(),
                List.of(codex, new FakeEngine()),
                activeRunRegistry,
                shipExecutor);
        RunnerConfig config = new RunnerConfig(
                1,
                List.of(
                        new EngineConfig("codex", null, null, null, null, true),
                        new EngineConfig("fake", null, null, null, null, true)),
                null,
                List.of(new RepoConfig(tempDir.toString(), false, false, "mvn test", "")));

        executor.execute(taskChange(), config, ACTOR_ID, "orchestrator-1");

        verify(apiClient).updateTaskStatus(
                TASK_ID,
                ACTOR_ID,
                "open",
                "Engine rate-limited and no fallback engine is configured/enabled.");
        verify(tmux, times(1)).sendKeys(
                anyString(), eq("export SBA_STAGE='build';"
                        + " export SBA_BASE_URL='http://127.0.0.1:8766'; 'codex-worker'"));
        verify(apiClient, never()).completeTask(any(), any(), any(), any(), any(), any(), any());
        verify(shipExecutor, never()).ship(
                any(), any(), any(), any(), any(), any(), any(), any());
        org.assertj.core.api.Assertions.assertThat(activeRunRegistry.tmuxSessionFor(TASK_ID)).isEmpty();
    }

    private TaskChange taskChange() {
        Instant now = Instant.parse("2026-07-15T12:00:00Z");
        Task task = new Task(
                TASK_ID,
                "spec-1",
                tempDir.toString(),
                "Implement worker result",
                "auto",
                TaskStatus.IN_PROGRESS,
                10,
                "test",
                ACTOR_ID,
                null,
                null,
                now,
                now);
        String body = "---\n"
                + "story: v1\n"
                + "repo: '" + tempDir.toString().replace("'", "''") + "'\n"
                + "mode: full_auto\n"
                + "verify: 'mvn test'\n"
                + "push: false\n"
                + "priority: 10\n"
                + "---\n"
                + "# Worker story\n\n"
                + "## Acceptance criteria\n- Work completes.\n";
        TaskSpec spec = new TaskSpec(
                "spec-1",
                tempDir.toString(),
                "Worker story",
                body,
                null,
                SpecStatus.ACTIVE,
                "test",
                now,
                now);
        return new TaskChange(new TaskSnapshot(task, spec), null);
    }

    private static ProcessResult processResult(List<String> command) {
        if (command.contains("rev-parse")) {
            return new ProcessResult(1, "", "origin/HEAD unavailable", false);
        }
        if (command.contains("symbolic-ref")) {
            return new ProcessResult(0, "main\n", "", false);
        }
        return new ProcessResult(0, "", "", false);
    }

    private static Engine engine(String id, String executable) {
        return new Engine() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public List<String> command(String prompt, EngineConfig config, File worktreeDir) {
                return List.of(executable);
            }
        };
    }
}
