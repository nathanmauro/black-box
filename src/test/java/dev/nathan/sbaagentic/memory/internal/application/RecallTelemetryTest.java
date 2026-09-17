package dev.nathan.sbaagentic.memory.internal.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nathan.sbaagentic.memory.MemoryEventReader;
import dev.nathan.sbaagentic.memory.MemoryEventReader.RecallCandidate;
import dev.nathan.sbaagentic.memory.MemoryRecallProperties;
import dev.nathan.sbaagentic.memory.MemorySearchOperations;
import dev.nathan.sbaagentic.memory.RecallRequestContext;
import dev.nathan.sbaagentic.memory.internal.adapter.in.mcp.MemoryMcpTools;
import dev.nathan.sbaagentic.memory.internal.adapter.in.web.ContextController;
import dev.nathan.sbaagentic.memory.internal.application.port.MemoryVectorStore;
import dev.nathan.sbaagentic.memory.internal.application.port.MemoryVectorStore.ScoredKey;
import dev.nathan.sbaagentic.memory.internal.application.port.TextEmbedder;
import dev.nathan.sbaagentic.memory.internal.domain.EmbeddingVector;
import dev.nathan.sbaagentic.recording.AgentEvent;
import dev.nathan.sbaagentic.recording.RecordingCaptureOperations;
import dev.nathan.sbaagentic.recording.RecordingCatalog;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.ai.chat.model.ToolContext;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RecallTelemetryTest {
    private final MemoryEventReader events = mock(MemoryEventReader.class);
    private final TextEmbedder embedder = mock(TextEmbedder.class);
    private final MemoryVectorStore vectors = mock(MemoryVectorStore.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final List<Map<String, Object>> records = new ArrayList<>();
    private final RecallTelemetry telemetry = new RecallTelemetry(registry, records::add, "safe-project");
    private final ContextService service = new ContextService(events, embedder, vectors,
            new MemoryRecallProperties(), telemetry);
    private final EmbeddingVector query = new EmbeddingVector("fixture", new float[] {1, 0});

    @BeforeEach
    void setup() {
        when(events.recall(anyList(), nullable(String.class), any(), anyInt())).thenReturn(List.of());
        when(events.recallCandidates(anyList(), any())).thenReturn(List.of());
        when(embedder.available()).thenReturn(true);
        when(embedder.embedQuery(anyString())).thenReturn(query);
        when(vectors.knn(any(), anyInt(), any())).thenReturn(List.of());
        when(vectors.fetchVectors(any(), anyString(), anyInt())).thenReturn(Map.of());
    }

    @Test
    void hybridGateSeparatesAttemptCompletionContributionAndRejectsPrivateData() throws Exception {
        AgentEvent accepted = event("private-event", "private recalled content");
        AgentEvent rejected = event("another-private-event", "other secret");
        when(events.recallCandidates(anyList(), any())).thenReturn(List.of(
                new RecallCandidate(accepted, "/private/project"), new RecallCandidate(rejected, "/private/other")));
        when(vectors.knn(any(), anyInt(), any())).thenReturn(List.of(
                new ScoredKey("event:private-event", .9), new ScoredKey("event:another-private-event", .2)));
        try (var context = RecallRequestContext.open("mcp", "codex", "audit", "safe-project")) {
            assertThat(service.recall("private query secret", 168, null).count()).isEqualTo(1);
            assertThat(record()).containsEntry("request_id", context.requestId());
        }
        assertThat(record()).containsEntry("mode", "hybrid").containsEntry("outcome", "success")
                .containsEntry("semantic_attempted", true).containsEntry("semantic_completed", true)
                .containsEntry("semantic_contributed", true).containsEntry("gate_admitted", 1)
                .containsEntry("gate_rejected", 1).containsEntry("semantic_candidates", 2)
                .containsEntry("embedding_outcome", "success").containsEntry("vector_outcome", "success")
                .containsEntry("result_count", 1).containsEntry("no_results", false)
                .containsEntry("semantic_returned", 1).containsEntry("relevance_floor", .61)
                .containsEntry("client", "codex").containsEntry("purpose", "audit")
                .containsEntry("project", "safe-project");
        String json = new ObjectMapper().writeValueAsString(record());
        assertThat(json).doesNotContain("private", "secret", "recalled content", "session", "fixture")
                .hasSizeLessThan(2000);
        assertThat(registry.get("blackbox.recall.requests").counter().count()).isEqualTo(1);
        assertThat(registry.get("blackbox.recall.duration").timer().count()).isEqualTo(1);
        assertThat(registry.getMeters()).allSatisfy(meter -> assertThat(meter.getId().getTags().toString())
                .doesNotContain("safe-project", "request", "private"));
        assertThat(RecallRequestContext.current()).isNull();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "/private/project", "019c7714-3b77-74d1-9866-e1f484aae2ab"})
    void locationAndBlankRequestsSkipSemanticDependencies(String scope) {
        service.recall(scope, 168, null);
        assertThat(record()).containsEntry("mode", "lexical").containsEntry("semantic_attempted", false)
                .containsEntry("semantic_completed", false).containsEntry("no_results", true)
                .containsEntry("embedding_probe_outcome", "skipped");
        verifyNoInteractions(embedder, vectors);
    }

    @Test
    void successfulSemanticSearchWithNoMatchesIsNotFallbackOrContribution() {
        service.recall("no matches", 168, null);
        assertThat(record()).containsEntry("mode", "hybrid").containsEntry("semantic_completed", true)
                .containsEntry("semantic_contributed", false).containsEntry("fallback_reason", "none")
                .containsEntry("no_results", true);
    }

    @Test
    void fusionContributionIsSeparateFromSemanticOverlapInTheFinalPage() {
        AgentEvent lexical = event("a-lexical", "text");
        AgentEvent semantic = event("z-semantic", "text");
        when(events.recall(anyList(), any(), any(), anyInt())).thenReturn(List.of(lexical));
        when(events.recallCandidates(anyList(), any())).thenReturn(List.of(new RecallCandidate(semantic, "/fixture")));
        when(vectors.knn(any(), anyInt(), any())).thenReturn(List.of(new ScoredKey("event:z-semantic", .9)));
        assertThat(service.recall("topic", 168, null, 1).items().getFirst().eventId()).isEqualTo("a-lexical");
        assertThat(record()).containsEntry("semantic_contributed", true).containsEntry("semantic_hits", 1)
                .containsEntry("semantic_returned", 0).containsEntry("result_count", 1);
    }

    @Test
    void embeddingUnavailableFallsBackWithoutCallingQueryOrVector() {
        when(embedder.available()).thenReturn(false);
        service.recall("topic", 168, null);
        assertThat(record()).containsEntry("mode", "lexical").containsEntry("semantic_attempted", true)
                .containsEntry("semantic_completed", false).containsEntry("fallback_reason", "embedding_unavailable")
                .containsEntry("embedding_probe_outcome", "unavailable");
        verify(embedder, never()).embedQuery(anyString());
        verifyNoInteractions(vectors);
    }

    @Test
    void embeddingErrorsPreserveLexicalResultsAndNeverExportExceptionText() {
        when(events.recall(anyList(), any(), any(), anyInt())).thenReturn(List.of(event("lexical", "text")));
        when(embedder.embedQuery(anyString())).thenThrow(new IllegalStateException("Bearer private-secret /private/path"));
        assertThat(service.recall("topic", 168, null).count()).isEqualTo(1);
        assertThat(record()).containsEntry("fallback_reason", "embedding_error").containsEntry("embedding_outcome", "error")
                .containsEntry("outcome", "success").containsEntry("error_category", "none");
        assertThat(record().toString()).doesNotContain("Bearer", "private-secret", "/private/path", "IllegalStateException");
    }

    @Test
    void availabilityExceptionAndCandidatesErrorAreDistinct() {
        when(embedder.available()).thenThrow(new IllegalStateException("secret"));
        service.recall("topic", 168, null);
        assertThat(record()).containsEntry("embedding_probe_outcome", "error").containsEntry("fallback_reason", "embedding_error");
        doReturn(true).when(embedder).available();
        when(events.recallCandidates(anyList(), any())).thenThrow(new IllegalStateException("secret"));
        records.clear();
        service.recall("topic", 168, null);
        assertThat(record()).containsEntry("embedding_probe_outcome", "success").containsEntry("fallback_reason", "candidates_error")
                .containsEntry("embedding_outcome", "skipped");
    }

    @Test
    void vectorSearchFailureFallsBackAndRetainsLexicalResults() {
        when(events.recall(anyList(), any(), any(), anyInt())).thenReturn(List.of(event("lexical", "text")));
        when(vectors.knn(any(), anyInt(), any())).thenThrow(new IllegalStateException("secret database path"));
        assertThat(service.recall("topic", 168, null).count()).isEqualTo(1);
        assertThat(record()).containsEntry("fallback_reason", "vector_error").containsEntry("vector_outcome", "error")
                .containsEntry("mode", "lexical").containsEntry("embedding_outcome", "success");
    }

    @Test
    void optionalVectorEnrichmentFailureDoesNotDiscardRankedResults() {
        when(events.recall(anyList(), any(), any(), anyInt())).thenReturn(List.of(event("lexical", "text")));
        when(vectors.fetchVectors(any(), anyString(), anyInt())).thenThrow(new IllegalStateException("secret database path"));
        var result = service.recall("topic", 168, null);
        assertThat(result.count()).isEqualTo(1);
        assertThat(result.items().getFirst().score()).isNull();
        assertThat(record()).containsEntry("vector_fetch_outcome", "error").containsEntry("mode", "hybrid")
                .containsEntry("fallback_reason", "none").containsEntry("outcome", "success");
    }

    @Test
    void realRecallFailureIsRecordedAndRethrown() {
        var failure = new IllegalStateException("sensitive SQL query");
        when(events.recall(anyList(), any(), any(), anyInt())).thenThrow(failure);
        assertThatThrownBy(() -> service.recall("topic", 168, null)).isSameAs(failure);
        assertThat(record()).containsEntry("outcome", "error").containsEntry("error_category", "recall_error")
                .containsEntry("no_results", false);
        assertThat(record().toString()).doesNotContain("sensitive SQL query");
        assertThat(RecallRequestContext.current()).isNull();
    }

    @Test
    void exporterAndMetricFailuresCannotChangeRecall() {
        var failingRegistry = mock(SimpleMeterRegistry.class);
        when(failingRegistry.counter(anyString(), any(Iterable.class))).thenThrow(new IllegalStateException("metrics failure"));
        var metricsFail = new RecallTelemetry(failingRegistry, records::add, "");
        new ContextService(events, embedder, vectors, new MemoryRecallProperties(), metricsFail).recall("", 168, null);
        assertThat(record()).containsEntry("outcome", "success");
        var loggingFail = new RecallTelemetry(registry, fields -> { throw new IllegalStateException("log failure"); }, "");
        assertThat(new ContextService(events, embedder, vectors, new MemoryRecallProperties(), loggingFail)
                .recall("", 168, null).count()).isZero();
        assertThat(registry.get("blackbox.recall.requests").counter().count()).isEqualTo(1);
    }

    @Test
    void httpRequestGeneratesCorrelationAndNormalizesUntrustedAttribution() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new ContextController(service,
                mock(RecordingCaptureOperations.class))).build();
        var result = mvc.perform(get("/api/recall").param("scope", "/private/path")
                .header("X-Blackbox-Client", "secret-client").header("X-Blackbox-Purpose", "Bearer secret")
                .header("X-Blackbox-Project", "not-allowlisted").header("X-Blackbox-Recall-Id", "attacker-id"))
                .andExpect(status().isOk()).andReturn();
        assertThat(record()).containsEntry("transport", "http").containsEntry("client", "unknown")
                .containsEntry("purpose", "unknown").containsEntry("project", "unknown");
        assertThat(result.getResponse().getHeader("X-Blackbox-Recall-Id"))
                .isEqualTo(record().get("request_id")).matches("[a-f0-9-]{36}").isNotEqualTo("attacker-id");
        assertThat(RecallRequestContext.current()).isNull();
    }

    @Test
    void mcpJsonCallbackPropagatesAttributionAndRestoresContext() {
        var tools = new MemoryMcpTools(mock(RecordingCatalog.class), service,
                mock(MemorySearchOperations.class), mock(RecordingCaptureOperations.class));
        var callback = java.util.Arrays.stream(tools.get())
                .filter(tool -> tool.getToolDefinition().name().equals("recallContext")).findFirst().orElseThrow();
        callback.call("""
                {"repoOrTopic":"/private/path","telemetryClient":"claude","telemetryPurpose":"test","telemetryProject":"safe-project"}
                """);
        assertThat(record()).containsEntry("transport", "mcp").containsEntry("client", "claude")
                .containsEntry("purpose", "test").containsEntry("project", "safe-project");
        assertThat(RecallRequestContext.current()).isNull();
        String firstId = (String) record().get("request_id");
        records.clear();
        callback.call("{\"repoOrTopic\":\"\"}");
        assertThat(record()).containsEntry("client", "unknown").containsEntry("purpose", "unknown")
                .containsEntry("project", "unknown");
        assertThat(record().get("request_id")).isNotEqualTo(firstId);
    }

    @Test
    void mcpInitializedClientWinsAndNestedContextRestoresEvenAfterFailure() {
        var tools = new MemoryMcpTools(mock(RecordingCatalog.class), service,
                mock(MemorySearchOperations.class), mock(RecordingCaptureOperations.class));
        var callback = java.util.Arrays.stream(tools.get())
                .filter(tool -> tool.getToolDefinition().name().equals("recallContext")).findFirst().orElseThrow();
        var exchange = mock(McpSyncServerExchange.class);
        when(exchange.getClientInfo()).thenReturn(new McpSchema.Implementation("codex-mcp-client", "private-version"));
        try (var outer = RecallRequestContext.open("internal", "manual", "test", null)) {
            callback.call("{\"repoOrTopic\":\"\",\"telemetryClient\":\"claude\"}", new ToolContext(Map.of("exchange", exchange)));
            assertThat(record()).containsEntry("client", "codex").containsEntry("transport", "mcp");
            assertThat(RecallRequestContext.current()).isSameAs(outer);
            records.clear();
            when(events.recall(anyList(), nullable(String.class), any(), anyInt())).thenThrow(new IllegalStateException("secret"));
            assertThatThrownBy(() -> callback.call("{\"repoOrTopic\":\"\"}", new ToolContext(Map.of("exchange", exchange))))
                    .isInstanceOf(RuntimeException.class);
            assertThat(record()).containsEntry("outcome", "error");
            assertThat(RecallRequestContext.current()).isSameAs(outer);
        }
        assertThat(RecallRequestContext.current()).isNull();
    }

    private Map<String, Object> record() {
        assertThat(records).hasSize(1);
        return records.getFirst();
    }

    private static AgentEvent event(String id, String text) {
        return new AgentEvent(id, "private-session", "codex", "private-client", null, "Decision", null,
                text, null, null, null, Map.of("decision", text, "repo", "/private/project"), Instant.now());
    }
}
