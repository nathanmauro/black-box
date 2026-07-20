package dev.nathan.sbaagentic.workflow;

/** Workflow DAG projections for task and session entry points. */
public interface DagOperations {

    DagResponse forTask(String taskId);

    DagResponse forSession(String sessionId);
}
