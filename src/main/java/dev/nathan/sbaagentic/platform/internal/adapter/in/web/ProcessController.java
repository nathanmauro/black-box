package dev.nathan.sbaagentic.platform.internal.adapter.in.web;

import java.util.List;

import dev.nathan.sbaagentic.platform.internal.application.ProcessMonitor;
import dev.nathan.sbaagentic.platform.internal.domain.AgentProcess;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoint for querying running agent processes.
 */
@RestController
@RequestMapping("/api")
public class ProcessController {

    private final ProcessMonitor processMonitor;

    public ProcessController(ProcessMonitor processMonitor) {
        this.processMonitor = processMonitor;
    }

    /**
     * Return the current snapshot of running agent processes.
     *
     * @return list of agent processes (empty if none running or monitoring unavailable)
     */
    @GetMapping("/processes")
    public List<AgentProcess> getProcesses() {
        return processMonitor.currentProcesses();
    }
}
