package dev.nathan.sbaagentic.platform.internal.application;

import java.util.List;

import dev.nathan.sbaagentic.platform.internal.adapter.in.sse.EventBroadcaster;
import dev.nathan.sbaagentic.platform.internal.domain.AgentProcess;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ProcessMonitorTest {

    private final EventBroadcaster broadcaster = mock(EventBroadcaster.class);
    private final ProcessMonitor monitor = new ProcessMonitor(broadcaster);

    @Test
    void currentProcesses_initiallyEmpty() {
        List<AgentProcess> processes = monitor.currentProcesses();
        assertThat(processes).isEmpty();
    }

    @Test
    void isAvailable_initiallyTrue() {
        assertThat(monitor.isAvailable()).isTrue();
    }
}
