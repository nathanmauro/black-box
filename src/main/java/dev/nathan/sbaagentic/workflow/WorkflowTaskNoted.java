package dev.nathan.sbaagentic.workflow;

import dev.nathan.sbaagentic.workflow.Task;
import dev.nathan.sbaagentic.workflow.TaskAnnotation;

/** A committed task annotation ready for publication. */
public record WorkflowTaskNoted(Task task, TaskAnnotation annotation, String observedAt) {
}
