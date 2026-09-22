package dev.nathan.sbaagentic.workflow;

/** A committed task annotation ready for publication. */
public record WorkflowTaskNoted(Task task, TaskAnnotation annotation, String observedAt) {}
