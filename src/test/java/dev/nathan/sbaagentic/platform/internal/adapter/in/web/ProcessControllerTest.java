package dev.nathan.sbaagentic.platform.internal.adapter.in.web;

import java.util.List;

import dev.nathan.sbaagentic.platform.internal.application.ProcessMonitor;
import dev.nathan.sbaagentic.platform.internal.domain.AgentProcess;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ProcessController.class)
class ProcessControllerTest {

    @Configuration
    static class TestConfig {
        @Bean
        ProcessMonitor processMonitor() {
            return mock(ProcessMonitor.class);
        }
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ProcessMonitor processMonitor;

    @Test
    void getProcesses_returnsEmpty_whenNone() throws Exception {
        when(processMonitor.currentProcesses()).thenReturn(List.of());

        mvc.perform(get("/api/processes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void getProcesses_returnsProcesses_whenPresent() throws Exception {
        AgentProcess process = new AgentProcess(12345, "claude", 12.5, 102400, "01:23:45");
        when(processMonitor.currentProcesses()).thenReturn(List.of(process));

        mvc.perform(get("/api/processes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].pid").value(12345))
                .andExpect(jsonPath("$[0].agent").value("claude"))
                .andExpect(jsonPath("$[0].cpuPercent").value(12.5))
                .andExpect(jsonPath("$[0].rssKb").value(102400))
                .andExpect(jsonPath("$[0].elapsed").value("01:23:45"));
    }
}
