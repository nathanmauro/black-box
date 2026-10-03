package dev.nathan.sbaagentic.memory.internal.adapter.in.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nathan.sbaagentic.memory.MemoryRecallOperations;
import dev.nathan.sbaagentic.memory.MemorySearchOperations;
import dev.nathan.sbaagentic.memory.RecallResult;
import dev.nathan.sbaagentic.memory.RecalledItem;
import dev.nathan.sbaagentic.memory.SearchResponse;
import dev.nathan.sbaagentic.memory.internal.application.EvidenceService;
import dev.nathan.sbaagentic.recording.CaptureDecisionRequest;
import dev.nathan.sbaagentic.recording.CaptureEvidenceRequest;
import dev.nathan.sbaagentic.recording.CaptureIdeaRequest;
import dev.nathan.sbaagentic.recording.CaptureProjectionRequest;
import dev.nathan.sbaagentic.recording.IngestResponse;
import dev.nathan.sbaagentic.recording.RecordingCaptureOperations;
import dev.nathan.sbaagentic.recording.RecordingCatalog;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.tool.ToolCallback;

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

    @Mock
    EvidenceService evidence;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    private MemoryMcpTools tools;

    @BeforeEach
    void setUp() {
        tools = new MemoryMcpTools(recordingCatalog, memoryRecall, memorySearch, captureOperations, evidence);
    }

    @Test
    void recallContextWithoutWithinHoursOrKindsFallsBackToServiceDefaults() {
        when(memoryRecall.recall(eq("sba-agentic"), eq(0), isNull(), isNull()))
                .thenReturn(new RecallResult("sba-agentic", 168, List.of(), 0, List.of(), "lexical"));

        String result = callback("recallContext").call("{\"repoOrTopic\":\"sba-agentic\"}");

        assertThat(result).contains("sba-agentic");
        verify(memoryRecall).recall(eq("sba-agentic"), eq(0), isNull(), isNull());
    }

    @Test
    void recallContextDefaultMaxCharsBoundsLargeResults() throws Exception {
        List<RecalledItem> items = java.util.stream.IntStream.range(0, 40)
                .mapToObj(index -> item("event-" + index, "headline-" + index, "r".repeat(3_000)))
                .toList();
        when(memoryRecall.recall(eq("sba-agentic"), eq(0), isNull(), isNull()))
                .thenReturn(new RecallResult("sba-agentic", 168, List.of("handoff"), items.size(), items, "hybrid"));

        RecallResult result = recallResult(callback("recallContext").call("{\"repoOrTopic\":\"sba-agentic\"}"));

        assertThat(result.truncated()).isTrue();
        assertThat(result.count()).isLessThan(40);
        assertThat(RecallResultClamp.cost(result)).isLessThanOrEqualTo(RecallResultClamp.DEFAULT_MAX_CHARS);
    }

    @Test
    void recallContextExplicitMaxCharsTrimsFirstOverflowingItem() throws Exception {
        List<RecalledItem> items = List.of(
                item("event-1", null, "a".repeat(600)),
                item("event-2", null, "b".repeat(600)),
                item("event-3", null, "c".repeat(600)));
        when(memoryRecall.recall(eq("sba-agentic"), eq(0), isNull(), isNull()))
                .thenReturn(new RecallResult("sba-agentic", 168, List.of("handoff"), items.size(), items, "hybrid"));

        RecallResult result = recallResult(callback("recallContext").call("""
                {"repoOrTopic":"sba-agentic","maxChars":1300}
                """));

        assertThat(result.truncated()).isTrue();
        assertThat(result.count()).isEqualTo(2);
        assertThat(result.items().getFirst()).isEqualTo(items.getFirst());
        assertThat(result.items().get(1).rationale()).contains("… (+");
        assertThat(result.items().get(1).rationale().length()).isLessThan(600);
        assertThat(result.items()).extracting(RecalledItem::eventId).containsExactly("event-1", "event-2");
        assertThat(RecallResultClamp.cost(result)).isLessThanOrEqualTo(1_300);
    }

    @Test
    void recallContextLeavesFittingItemsByteIdenticalAndUntruncated() throws Exception {
        List<RecalledItem> items = List.of(
                new RecalledItem(
                        "event-1",
                        "session-1",
                        "decision",
                        "codex",
                        "client-1",
                        "/repo",
                        Instant.parse("2026-08-28T12:00:00Z"),
                        "Keep MCP result bounded",
                        "Claude Code rejects oversized tool results.",
                        List.of("Raise every client cap", "Return opaque handles"),
                        0.8,
                        List.of("Run contract tests"),
                        "Ship the adapter clamp.",
                        "next-agent",
                        0.91),
                item("event-2", "Second item", "Still comfortably under the cap."));
        when(memoryRecall.recall(eq("sba-agentic"), eq(0), isNull(), isNull()))
                .thenReturn(new RecallResult("sba-agentic", 168, List.of("decision"), items.size(), items, "hybrid"));

        RecallResult result = recallResult(callback("recallContext").call("""
                {"repoOrTopic":"sba-agentic","maxChars":5000}
                """));

        assertThat(result.truncated()).isFalse();
        assertThat(result.count()).isEqualTo(items.size());
        assertThat(result.items()).containsExactlyElementsOf(items);
        assertThat(RecallResultClamp.cost(result))
                .isEqualTo(RecallResultClamp.cost(
                        new RecallResult("sba-agentic", 168, List.of("decision"), items.size(), items, "hybrid")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"observation", "projection", "evidence"})
    void capturedBodyUsesTheBudgetAndReportsExactlyWhatWasRemoved(String kind) throws Exception {
        String body = "Observation evidence\n" + "🧪".repeat(3000);
        RecalledItem observation = new RecalledItem(
                "observation-1",
                "session-1",
                kind,
                "codex",
                "client-1",
                "/repo",
                Instant.parse("2026-08-28T12:00:00Z"),
                "Observation evidence",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                body);
        when(memoryRecall.recall(eq("sba-agentic"), eq(0), isNull(), isNull()))
                .thenReturn(new RecallResult("sba-agentic", 168, List.of(kind), 1, List.of(observation), "lexical"));
        RecallResult result =
                recallResult(callback("recallContext").call("{\"repoOrTopic\":\"sba-agentic\",\"maxChars\":700}"));
        assertThat(result.truncated()).isTrue();
        assertThat(result.count()).isEqualTo(1);
        RecalledItem item = result.items().getFirst();
        assertThat(item.headline()).isEqualTo(observation.headline());
        assertThat(item.eventId()).isEqualTo(observation.eventId());
        assertThat(item.sessionId()).isEqualTo(observation.sessionId());
        assertThat(item.observedAt()).isEqualTo(observation.observedAt());
        int suffixStart = item.body().lastIndexOf("… (+");
        String prefix = item.body().substring(0, suffixStart);
        assertThat(body).startsWith(prefix);
        assertThat(Character.isHighSurrogate(prefix.charAt(prefix.length() - 1)))
                .isFalse();
        assertThat(item.body().substring(suffixStart))
                .isEqualTo("… (+" + (body.length() - prefix.length()) + " chars)");
        assertThat(RecallResultClamp.cost(result)).isLessThanOrEqualTo(700);
        assertThat(observation.body()).isEqualTo(body);
    }

    @Test
    void recallContextRaisesExplicitMaxCharsToFiveHundredFloor() throws Exception {
        List<RecalledItem> items = List.of(item("event-1", null, "r".repeat(290)));
        when(memoryRecall.recall(eq("sba-agentic"), eq(0), isNull(), isNull()))
                .thenReturn(new RecallResult("sba-agentic", 168, List.of("handoff"), items.size(), items, "hybrid"));

        RecallResult result = recallResult(callback("recallContext").call("""
                {"repoOrTopic":"sba-agentic","maxChars":100}
                """));

        assertThat(result.truncated()).isFalse();
        assertThat(result.count()).isEqualTo(1);
        assertThat(result.items()).containsExactlyElementsOf(items);
        assertThat(RecallResultClamp.cost(result)).isLessThanOrEqualTo(RecallResultClamp.MIN_MAX_CHARS);
    }

    @Test
    void recallResultSixArgumentConstructionDefaultsToUntruncated() {
        RecallResult result = new RecallResult("sba-agentic", 168, List.of("handoff"), 0, List.of(), "lexical");

        assertThat(result.truncated()).isFalse();
    }

    @Test
    void recentSessionsWithoutLimitDefaultsToTen() {
        when(recordingCatalog.recentSessions(10, false, false)).thenReturn(List.of());

        callback("recentSessions").call("{}");

        verify(recordingCatalog).recentSessions(10, false, false);
    }

    @Test
    void recentSessionsPassesHumanOnly() {
        when(recordingCatalog.recentSessions(5, false, true)).thenReturn(List.of());

        callback("recentSessions").call("{\"limit\":5,\"humanOnly\":true}");

        verify(recordingCatalog).recentSessions(5, false, true);
    }

    @Test
    void searchSessionsWithoutLimitDefaultsToTen() {
        when(memorySearch.search("jar swap", 10, false))
                .thenReturn(new SearchResponse("jar swap", List.of(), List.of(), null));

        callback("searchSessions").call("{\"query\":\"jar swap\"}");

        verify(memorySearch).search("jar swap", 10, false);
    }

    @Test
    void searchSessionsPassesHumanOnly() {
        when(memorySearch.search("jar swap", 3, true))
                .thenReturn(new SearchResponse("jar swap", List.of(), List.of(), null));

        callback("searchSessions").call("{\"query\":\"jar swap\",\"limit\":3,\"humanOnly\":true}");

        verify(memorySearch).search("jar swap", 3, true);
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

    @Test
    void captureProjectionDelegatesStructuredPaths() {
        when(captureOperations.captureProjection(any(CaptureProjectionRequest.class)))
                .thenReturn(new IngestResponse("e3", "s1", "codex", "c1", "Projection", false));

        String result = callback("captureProjection").call("""
                {"source":"codex","clientSessionId":"c1","repo":"/tmp/repo",
                 "basis":"head handoff left graph capture open",
                 "paths":[
                   {"title":"Ship projection capture","description":"Add MCP and REST capture surfaces.","confidence":0.72},
                   {"title":"Tune graph ranking","description":"Use captured paths as ghost futures.","confidence":0.54}
                 ]}
                """);

        assertThat(result).contains("e3");
        ArgumentCaptor<CaptureProjectionRequest> captor = ArgumentCaptor.forClass(CaptureProjectionRequest.class);
        verify(captureOperations).captureProjection(captor.capture());
        CaptureProjectionRequest request = captor.getValue();
        assertThat(request.source()).isEqualTo("codex");
        assertThat(request.clientSessionId()).isEqualTo("c1");
        assertThat(request.repo()).isEqualTo("/tmp/repo");
        assertThat(request.basis()).isEqualTo("head handoff left graph capture open");
        assertThat(request.paths()).hasSize(2);
        assertThat(request.paths().getFirst().title()).isEqualTo("Ship projection capture");
        assertThat(request.paths().getFirst().description()).isEqualTo("Add MCP and REST capture surfaces.");
        assertThat(request.paths().getFirst().confidence()).isEqualTo(0.72);
    }

    @Test
    void captureEvidenceAndRecallIdeaAreRegistered() {
        when(captureOperations.captureEvidence(any(CaptureEvidenceRequest.class)))
                .thenReturn(new IngestResponse("evidence-1", "session-1", "codex", "c1", "Evidence", false));
        String result = callback("captureEvidence").call("""
                {"source":"codex","clientSessionId":"c1","claim":"Command found zero matches",
                 "sourceRef":"rg run","supports":["idea:sample"],
                 "alsoIn":[{"project":"Other","score":0.7}]}
                """);
        assertThat(result).contains("evidence-1");
        ArgumentCaptor<CaptureEvidenceRequest> capture = ArgumentCaptor.forClass(CaptureEvidenceRequest.class);
        verify(captureOperations).captureEvidence(capture.capture());
        assertThat(capture.getValue().alsoIn().getFirst().project()).isEqualTo("Other");
        callback("recallIdea").call("{\"ideaKey\":\"sample\"}");
        verify(evidence).detail("sample");
    }

    @Test
    void captureIdeaDelegatesEveryField() {
        when(captureOperations.captureIdea(any(CaptureIdeaRequest.class)))
                .thenReturn(new IngestResponse("e4", "s1", "claude", "c1", "Idea", false));

        String result = callback("captureIdea").call("""
                {"source":"claude","clientSessionId":"c1","repo":"/tmp/repo",
                 "title":"Lanes board","oneLiner":"One swimlane per project.","origin":"human-aside",
                 "quote":"a live board with lanes","sourceRef":"session-9","legs":8,"status":"untouched",
                 "connects":["Orbit (NAT-196)","project identity"],"resumeStep":"sketch lanes",
                 "link":"https://example.com/idea","notes":"Prior art","ideaKey":"repo-lanes-board"}
                """);

        assertThat(result).contains("e4");
        ArgumentCaptor<CaptureIdeaRequest> captor = ArgumentCaptor.forClass(CaptureIdeaRequest.class);
        verify(captureOperations).captureIdea(captor.capture());
        assertThat(captor.getValue())
                .isEqualTo(new CaptureIdeaRequest(
                        "claude",
                        "c1",
                        "/tmp/repo",
                        "Lanes board",
                        "One swimlane per project.",
                        "human-aside",
                        "a live board with lanes",
                        "session-9",
                        8,
                        "untouched",
                        List.of("Orbit (NAT-196)", "project identity"),
                        "sketch lanes",
                        "https://example.com/idea",
                        "Prior art",
                        "repo-lanes-board"));
    }

    @Test
    void captureIdeaToleratesOmittedOptionalFields() {
        when(captureOperations.captureIdea(any(CaptureIdeaRequest.class)))
                .thenReturn(new IngestResponse("e5", "s1", "codex", "c1", "Idea", false));

        callback("captureIdea").call("""
                {"source":"codex","clientSessionId":"c1","title":"Evidence kind",
                 "oneLiner":"Make Evidence first-class.","origin":"agent-proposed"}
                """);

        ArgumentCaptor<CaptureIdeaRequest> captor = ArgumentCaptor.forClass(CaptureIdeaRequest.class);
        verify(captureOperations).captureIdea(captor.capture());
        CaptureIdeaRequest request = captor.getValue();
        assertThat(request.title()).isEqualTo("Evidence kind");
        assertThat(request.origin()).isEqualTo("agent-proposed");
        assertThat(request.repo()).isNull();
        assertThat(request.legs()).isNull();
        assertThat(request.status()).isNull();
        assertThat(request.connects()).isNull();
        assertThat(request.ideaKey()).isNull();
    }

    @Test
    void captureIdeaToolSaysWhenToUseIt() {
        assertThat(callback("captureIdea").getToolDefinition().description())
                .contains("not being acted on right now")
                .contains("human's aside or an agent's suggestion");
        assertThat(callback("captureIdea").getToolDefinition().inputSchema())
                .contains("human-aside")
                .contains("agent-proposed")
                .contains("untouched");
    }

    @Test
    void recallContextPassesTheIdeaKind() {
        when(memoryRecall.recall(eq("/tmp/repo"), eq(0), eq(List.of("idea")), isNull()))
                .thenReturn(new RecallResult("/tmp/repo", 168, List.of("idea"), 0, List.of(), "lexical"));

        callback("recallContext").call("{\"repoOrTopic\":\"/tmp/repo\",\"kinds\":[\"idea\"]}");

        verify(memoryRecall).recall(eq("/tmp/repo"), eq(0), eq(List.of("idea")), isNull());
    }

    private ToolCallback callback(String name) {
        for (ToolCallback callback : tools.get()) {
            if (callback.getToolDefinition().name().equals(name)) {

                return callback;
            }
        }
        throw new IllegalStateException("no tool named " + name);
    }

    private RecallResult recallResult(String result) throws Exception {

        return objectMapper.readValue(result, RecallResult.class);
    }

    private static RecalledItem item(String id, String headline, String rationale) {

        return new RecalledItem(
                id,
                "session-" + id,
                "handoff",
                "codex",
                "client-" + id,
                "/repo",
                Instant.parse("2026-08-28T12:00:00Z"),
                headline,
                rationale,
                List.of(),
                null,
                List.of(),
                null,
                null,
                null);
    }
}
