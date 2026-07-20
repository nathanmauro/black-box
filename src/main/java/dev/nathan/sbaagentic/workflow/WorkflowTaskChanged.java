package dev.nathan.sbaagentic.workflow;

import dev.nathan.sbaagentic.workflow.Task;

/** A committed task lifecycle change ready for publication. */
public record WorkflowTaskChanged(
        Task task,
        String transitionId,
        String transitionType,
        String observedAt) {
}
