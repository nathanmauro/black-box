package dev.nathan.sbaagentic.runner;

import dev.nathan.sbaagentic.runner.internal.client.blackbox.TaskChange;

import org.springframework.stereotype.Component;

@Component
public class SdlcReviewCycle {

    private final RunExecutor runExecutor;

    public SdlcReviewCycle(RunExecutor runExecutor) {
        this.runExecutor = runExecutor;
    }

    public void execute(
            TaskChange claimedReviewTask,
            RunnerConfig config,
            String actorId,
            String orchestratorSessionId) {
        runExecutor.executeReview(claimedReviewTask, config, actorId, orchestratorSessionId);
    }
}
