package dev.nathan.sbaagentic.workflow;

/**
 * Outbound workflow publication boundary. Implementations are best-effort notification adapters;
 * canonical task mutations are already committed before these methods are invoked.
 */
public interface WorkflowPublication {

    void taskChanged(WorkflowTaskChanged event);

    void taskNoted(WorkflowTaskNoted event);
}
