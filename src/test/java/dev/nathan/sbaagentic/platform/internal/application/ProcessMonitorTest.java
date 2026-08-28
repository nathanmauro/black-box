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

    // --- parsePsLine tests ---

    @Test
    void parsePsLine_matchesClaudeWithFullPath() {
        String line = " 12345   1234  123456  12.3  01:23:45 /Applications/Claude.app/Contents/MacOS/Claude";
        AgentProcess result = monitor.parsePsLine(line);
        
        assertThat(result).isNotNull();
        assertThat(result.pid()).isEqualTo(12345);
        assertThat(result.agent()).isEqualTo("claude");
        assertThat(result.cpuPercent()).isEqualTo(12.3);
        assertThat(result.rssKb()).isEqualTo(123456);
        assertThat(result.elapsed()).isEqualTo("01:23:45");
    }

    @Test
    void parsePsLine_matchesCodexBareName() {
        String line = " 67890   1234  456789  25.1     12:34 codex";
        AgentProcess result = monitor.parsePsLine(line);
        
        assertThat(result).isNotNull();
        assertThat(result.pid()).isEqualTo(67890);
        assertThat(result.agent()).isEqualTo("codex");
        assertThat(result.cpuPercent()).isEqualTo(25.1);
        assertThat(result.rssKb()).isEqualTo(456789);
        assertThat(result.elapsed()).isEqualTo("12:34");
    }

    @Test
    void parsePsLine_matchesCursorCli() {
        String line = " 54321   1234  789012   5.0 3-04:56:78 /usr/local/bin/cursor";
        AgentProcess result = monitor.parsePsLine(line);
        
        assertThat(result).isNotNull();
        assertThat(result.pid()).isEqualTo(54321);
        assertThat(result.agent()).isEqualTo("cursor");
        assertThat(result.cpuPercent()).isEqualTo(5.0);
        assertThat(result.rssKb()).isEqualTo(789012);
        assertThat(result.elapsed()).isEqualTo("3-04:56:78");
    }

    @Test
    void parsePsLine_matchesRaycast() {
        String line = " 11111   1234  234567   8.5     45:12 /Applications/Raycast.app/Contents/MacOS/Raycast";
        AgentProcess result = monitor.parsePsLine(line);
        
        assertThat(result).isNotNull();
        assertThat(result.agent()).isEqualTo("raycast");
    }

    @Test
    void parsePsLine_ignoresNonAgentProcess() {
        String line = " 99999   1234  100000   1.0     00:10 /usr/bin/python";
        AgentProcess result = monitor.parsePsLine(line);
        
        assertThat(result).isNull();
    }

    @Test
    void parsePsLine_ignoresPathContainingAgentName() {
        // Should NOT match "cursor" as substring in path
        String line = " 88888   1234  200000   2.0     05:00 /Users/cursor/bin/helper";
        AgentProcess result = monitor.parsePsLine(line);
        
        assertThat(result).isNull();
    }

    @Test
    void parsePsLine_handlesMalformedLine() {
        String line = "not a valid ps line";
        AgentProcess result = monitor.parsePsLine(line);
        
        assertThat(result).isNull();
    }

    @Test
    void parsePsLine_handlesEmptyLine() {
        AgentProcess result = monitor.parsePsLine("");
        assertThat(result).isNull();
    }

    @Test
    void parsePsLine_handlesNonNumericFields() {
        String line = " abc   1234  def  12.3  01:23:45 /path/to/claude";
        AgentProcess result = monitor.parsePsLine(line);
        
        assertThat(result).isNull();
    }

    // --- identifyAgent tests ---

    @Test
    void identifyAgent_matchesExactBasename() {
        assertThat(monitor.identifyAgent("claude")).isEqualTo("claude");
        assertThat(monitor.identifyAgent("codex")).isEqualTo("codex");
        assertThat(monitor.identifyAgent("cursor")).isEqualTo("cursor");
        assertThat(monitor.identifyAgent("raycast")).isEqualTo("raycast");
    }

    @Test
    void identifyAgent_matchesBasenameWithExtension() {
        assertThat(monitor.identifyAgent("claude.exe")).isEqualTo("claude");
        assertThat(monitor.identifyAgent("cursor.app")).isEqualTo("cursor");
    }

    @Test
    void identifyAgent_matchesFullPath() {
        assertThat(monitor.identifyAgent("/usr/local/bin/codex")).isEqualTo("codex");
        assertThat(monitor.identifyAgent("/Applications/Claude.app/Contents/MacOS/Claude")).isEqualTo("claude");
    }

    @Test
    void identifyAgent_doesNotMatchSubstring() {
        assertThat(monitor.identifyAgent("/Users/cursor/bin/helper")).isNull();
        assertThat(monitor.identifyAgent("cursor-helper")).isNull();
        assertThat(monitor.identifyAgent("my-codex-tool")).isNull();
    }

    @Test
    void identifyAgent_handlesWindowsPaths() {
        assertThat(monitor.identifyAgent("C:\\Program Files\\Claude\\claude.exe")).isEqualTo("claude");
    }

    @Test
    void identifyAgent_isCaseInsensitive() {
        assertThat(monitor.identifyAgent("CLAUDE")).isEqualTo("claude");
        assertThat(monitor.identifyAgent("Cursor")).isEqualTo("cursor");
    }

    @Test
    void identifyAgent_returnsNullForUnknown() {
        assertThat(monitor.identifyAgent("python")).isNull();
        assertThat(monitor.identifyAgent("/usr/bin/bash")).isNull();
    }
}
