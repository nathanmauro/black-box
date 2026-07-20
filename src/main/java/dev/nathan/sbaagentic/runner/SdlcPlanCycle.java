package dev.nathan.sbaagentic.runner;

import dev.nathan.sbaagentic.workflow.TaskChange;

import org.springframework.stereotype.Component;

@Component
public class SdlcPlanCycle {

    private final RunExecutor runExecutor;

    public SdlcPlanCycle(RunExecutor runExecutor) {
        this.runExecutor = runExecutor;
    }

    public void execute(
            TaskChange claimedPlanTask,
            RunnerConfig config,
            String actorId,
            String orchestratorSessionId) {
        runExecutor.executePlan(claimedPlanTask, config, actorId, orchestratorSessionId);
    }
}
