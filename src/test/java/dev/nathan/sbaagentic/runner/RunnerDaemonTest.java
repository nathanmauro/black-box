package dev.nathan.sbaagentic.runner;

import dev.nathan.sbaagentic.runner.internal.client.blackbox.BlackBoxApiClient;

import java.util.List;
import java.util.concurrent.Executor;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nathan.sbaagentic.runner.process.TmuxController;
import dev.nathan.sbaagentic.runner.run.ActiveRunRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class RunnerDaemonTest {

    private static final RunnerConfig CONFIG = new RunnerConfig(1, List.of(), null, List.of());

    @Mock
    BlackBoxApiClient apiClient;

    @Mock
    CrashRecovery crashRecovery;

    @Mock
    GateCycle gateCycle;

    @Mock
    AutoCycle autoCycle;

    @Mock
    SdlcPlanCycle planCycle;

    @Mock
    SdlcReviewCycle reviewCycle;

    @Mock
    SdlcApprovalReconciler approvalReconciler;

    @Mock
    TmuxController tmux;

    private ActiveRunRegistry activeRunRegistry;
    private RunnerDaemon daemon;

    @BeforeEach
    void setUp() {
        activeRunRegistry = new ActiveRunRegistry();
        daemon = new RunnerDaemon(
                apiClient,
                crashRecovery,
                gateCycle,
                autoCycle,
                planCycle,
                reviewCycle,
                approvalReconciler,
                new ObjectMapper(),
                tmux,
                activeRunRegistry);
    }

    @Test
    void approvalSseWakeReconcilesAuthoritativelyWithoutClaimOwnership() {
        Executor directExecutor = Runnable::run;

        daemon.onSseFrame(
                "task.note",
                """
                        {
                          "task": {"id":"plan-1","lane":"sdlc:plan","status":"done"},
                          "annotation": {
                            "kind":"approval",
                            "dataJson":{"decision":"approve","stage":"plan"}
                          }
                        }
                        """,
                CONFIG,
                directExecutor);

        verify(approvalReconciler).reconcileTask("plan-1", CONFIG, "blackbox-runner");
    }

    @Test
    void steeringStillRequiresRunnerClaimAndAnActiveRun() {
        activeRunRegistry.register("task-1", "bb-run-task-1");

        daemon.onSseFrame(
                "task.note",
                """
                        {
                          "task": {"id":"task-1","claimedBy":"someone-else"},
                          "annotation": {"kind":"steer","text":"Please check the edge case."}
                        }
                        """,
                CONFIG,
                Runnable::run);

        verify(tmux, never()).sendKeys("bb-run-task-1", "Please check the edge case.");
        verify(apiClient, never()).annotate(
                "task-1",
                "blackbox-runner",
                "progress",
                "Steering injected into active run.",
                null);
    }
}
