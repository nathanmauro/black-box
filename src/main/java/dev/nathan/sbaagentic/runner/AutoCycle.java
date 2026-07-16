package dev.nathan.sbaagentic.runner;

import dev.nathan.sbaagentic.task.TaskChange;

public interface AutoCycle {

    void execute(TaskChange claimedAutoTask, RunnerConfig config, String actorId, String orchestratorSessionId);
}
