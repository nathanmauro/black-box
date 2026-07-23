package dev.nathan.sbaagentic.memory.internal.adapter.in.mcp;

import java.util.List;

import dev.nathan.sbaagentic.memory.MemoryRecallOperations;
import dev.nathan.sbaagentic.memory.MemorySearchOperations;
import dev.nathan.sbaagentic.memory.RecallResult;
import dev.nathan.sbaagentic.memory.SearchResponse;
import dev.nathan.sbaagentic.recording.CaptureDecisionRequest;
import dev.nathan.sbaagentic.recording.IngestResponse;
import dev.nathan.sbaagentic.recording.RecordingCaptureOperations;
import dev.nathan.sbaagentic.recording.RecordingCatalog;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.ai.tool.ToolCallback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MCP clients routinely omit parameters they consider optional, so every tool argument must
 * survive arriving as JSON {@code null}. These tests exercise the real callback JSON path the
 * MCP server uses, not the Java methods directly.
 */
@ExtendWith(MockitoExtension.class)
class MemoryMcpToolsTest {

    @Mock
    RecordingCatalog recordingCatalog;

    @Mock
    MemoryRecallOperations memoryRecall;

    @Mock
    MemorySearchOperations memorySearch;

    @Mock
    RecordingCaptureOperations captureOperations;

    private MemoryMcpTools tools;

    @BeforeEach
    void setUp() {
        tools = new MemoryMcpTools(recordingCatalog, memoryRecall, memorySearch, captureOperations);
    }

    @Test
    void recallContextWithoutWithinHoursOrKindsFallsBackToServiceDefaults() {
        when(memoryRecall.recall(eq("sba-agentic"), eq(0), isNull()))
                .thenReturn(new RecallResult("sba-agentic", 168, List.of(), 0, List.of()));

        String result = callback("recallContext").call("{\"repoOrTopic\":\"sba-agentic\"}");

        assertThat(result).contains("sba-agentic");
        verify(memoryRecall).recall(eq("sba-agentic"), eq(0), isNull());
    }

    @Test
    void recentSessionsWithoutLimitDefaultsToTen() {
        when(recordingCatalog.recentSessions(10)).thenReturn(List.of());

        callback("recentSessions").call("{}");

        verify(recordingCatalog).recentSessions(10);
    }

    @Test
    void searchSessionsWithoutLimitDefaultsToTen() {
        when(memorySearch.search("jar swap", 10))
                .thenReturn(new SearchResponse("jar swap", List.of(), List.of(), null));

        callback("searchSessions").call("{\"query\":\"jar swap\"}");

        verify(memorySearch).search("jar swap", 10);
    }

    @Test
    void captureDecisionWithoutConfidenceRecordsNullConfidence() {
        when(captureOperations.captureDecision(any(CaptureDecisionRequest.class)))
                .thenReturn(new IngestResponse("e1", "s1", "claude", "c1", "Decision", false));

        String result = callback("captureDecision").call("""
                {"source":"claude","clientSessionId":"c1","repo":"/tmp/repo",
                 "decision":"use boxed params","rationale":"omitted MCP args arrive as null"}
                """);

        assertThat(result).contains("e1");
        ArgumentCaptor<CaptureDecisionRequest> captor = ArgumentCaptor.forClass(CaptureDecisionRequest.class);
        verify(captureOperations).captureDecision(captor.capture());
        assertThat(captor.getValue().confidence()).isNull();
        assertThat(captor.getValue().decision()).isEqualTo("use boxed params");
    }

    @Test
    void captureDecisionKeepsProvidedConfidence() {
        when(captureOperations.captureDecision(any(CaptureDecisionRequest.class)))
                .thenReturn(new IngestResponse("e2", "s1", "claude", "c1", "Decision", false));

        callback("captureDecision").call("""
                {"source":"claude","clientSessionId":"c1","repo":"/tmp/repo",
                 "decision":"ship it","rationale":"verified","confidence":0.85}
                """);

        ArgumentCaptor<CaptureDecisionRequest> captor = ArgumentCaptor.forClass(CaptureDecisionRequest.class);
        verify(captureOperations).captureDecision(captor.capture());
        assertThat(captor.getValue().confidence()).isEqualTo(0.85);
    }

    private ToolCallback callback(String name) {
        for (ToolCallback callback : tools.get()) {
            if (callback.getToolDefinition().name().equals(name)) {
                return callback;
            }
        }
        throw new IllegalStateException("no tool named " + name);
    }
}
