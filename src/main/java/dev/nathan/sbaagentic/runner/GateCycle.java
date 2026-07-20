package dev.nathan.sbaagentic.runner;

import dev.nathan.sbaagentic.workflow.TaskChange;

public interface GateCycle {

    void evaluate(TaskChange claimedGateTask, RunnerConfig config, String actorId);
}
