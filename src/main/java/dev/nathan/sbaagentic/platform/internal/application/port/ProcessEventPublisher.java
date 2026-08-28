package dev.nathan.sbaagentic.platform.internal.application.port;

import java.util.List;

import dev.nathan.sbaagentic.platform.internal.domain.AgentProcess;

/**
 * Outbound port for publishing process monitoring events.
 * <p>
 * Implemented by SSE broadcaster in the adapter layer.
 */
public interface ProcessEventPublisher {

    /**
     * Publish the current snapshot of running agent processes.
     *
     * @param processes current agent processes
     * @param available whether process monitoring is available
     */
    void publishProcesses(List<AgentProcess> processes, boolean available);
}
