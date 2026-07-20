package dev.nathan.sbaagentic.runner;

import dev.nathan.sbaagentic.runner.internal.client.blackbox.TaskChange;

public interface GateCycle {

    void evaluate(TaskChange claimedGateTask, RunnerConfig config, String actorId);
}
