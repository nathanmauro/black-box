package dev.nathan.sbaagentic.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nathan.sbaagentic.memory.MemoryRecallOperations;
import dev.nathan.sbaagentic.memory.RecallResult;
import dev.nathan.sbaagentic.memory.RecalledItem;
import dev.nathan.sbaagentic.project.ProjectGraphOperations;
import dev.nathan.sbaagentic.project.ProjectKey;
import dev.nathan.sbaagentic.project.ProjectTrajectoryResponse;
import dev.nathan.sbaagentic.recording.AgentEvent;
import dev.nathan.sbaagentic.recording.AgentSession;
import dev.nathan.sbaagentic.recording.CaptureDecisionRequest;
import dev.nathan.sbaagentic.recording.CaptureHandoffRequest;
import dev.nathan.sbaagentic.recording.CaptureProjectionRequest;
import dev.nathan.sbaagentic.recording.IngestResponse;
import dev.nathan.sbaagentic.recording.ProjectionPath;
import dev.nathan.sbaagentic.recording.RecordingCaptureOperations;
import dev.nathan.sbaagentic.recording.internal.adapter.out.sqlite.RecordingSqlStore;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Exercises the write+query loop end to end: an agent commits structured intent, and a later recall
 * — scoped by repo or topic — reads that intent back as typed fields, not raw text. This is the
 * behavior that distinguishes Black Box from a read-only timeline, so it earns direct coverage.
 */
@SpringBootTest(
        properties = {
            // A temp file DB takes the production WAL + busy_timeout path; cache=shared
            // memory throws SQLITE_LOCKED on writer collisions, ignoring busy_timeout.
            "spring.datasource.url=jdbc:sqlite:${java.io.tmpdir}/bb-context-loop-test-${random.uuid}.db",
            "sba.local-ai.enabled=false",
            "sba.elasticsearch.enabled=false",
            "sba.memory.embedding.enabled=false"
        })
@AutoConfigureMockMvc
class ContextLoopTest {

    @Autowired
    MemoryRecallOperations contextService;

    @Autowired
    RecordingCaptureOperations captureOperations;

    @Autowired
    RecordingSqlStore repository;

    @Autowired
    ProjectGraphOperations projectGraph;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void decisionRoundTripsAsStructuredIntentRecallableByRepo() {
        captureOperations.captureDecision(new CaptureDecisionRequest(
                "codex",
                "codex-1",
                "/tmp/acme-roundtrip",
                "Use JWT refresh-rotation for auth",
                "Stateless and horizontally scalable",
                List.of("Server-side sessions in Redis"),
                0.8,
                List.of("revoke-on-logout not wired yet")));

        RecallResult byRepo = contextService.recall("/tmp/acme-roundtrip", 168, List.of("decision"));
        assertThat(byRepo.count()).isEqualTo(1);
        RecalledItem item = byRepo.items().getFirst();
        AgentSession session = repository.findSession("codex", "codex-1").orElseThrow();
        assertThat(item.sessionId()).isEqualTo(session.id());
        assertThat(item.kind()).isEqualTo("decision");
        assertThat(item.source()).isEqualTo("codex");
        assertThat(item.headline()).contains("JWT refresh-rotation");
        assertThat(item.rationale()).contains("Stateless");
        assertThat(item.alternatives()).containsExactly("Server-side sessions in Redis");
        assertThat(item.confidence()).isEqualTo(0.8);
        assertThat(item.openLoops()).containsExactly("revoke-on-logout not wired yet");
        assertThat(item.repo()).isEqualTo("/tmp/acme-roundtrip");
    }

    @Test
    void recallMatchesByTopicInTextNotJustRepo() {
        captureOperations.captureDecision(new CaptureDecisionRequest(
                "codex",
                "codex-topic",
                "/tmp/other-repo",
                "Adopt structured logging with correlation ids",
                "Traceability across services",
                List.of(),
                0.6,
                List.of()));

        // The repo does not match, but the topic appears in the decision text.
        RecallResult byTopic = contextService.recall("correlation", 168, List.of("decision"));
        assertThat(byTopic.count()).isEqualTo(1);
        assertThat(byTopic.items().getFirst().headline()).contains("structured logging");
    }

    @Test
    void handoffIsRecalledByDefaultAndCarriesOpenLoops() {
        captureOperations.captureHandoff(new CaptureHandoffRequest(
                "claude",
                "claude-7",
                "/tmp/checkout",
                "next-session",
                "Wired the payment intent flow",
                List.of("webhook signature check missing"),
                "Verify the Stripe webhook secret in staging"));

        // Default kinds (decision + handoff) must surface the handoff.
        RecallResult recalled = contextService.recall("/tmp/checkout", 168, null);
        assertThat(recalled.kinds()).containsExactly("decision", "handoff");
        assertThat(recalled.count()).isEqualTo(1);
        RecalledItem item = recalled.items().getFirst();
        assertThat(item.kind()).isEqualTo("handoff");
        assertThat(item.toAgent()).isEqualTo("next-session");
        assertThat(item.openLoops()).containsExactly("webhook signature check missing");
        assertThat(item.nextAction()).contains("Stripe webhook secret");
    }

    @Test
    void handoffEventIdIsADirectRecallKey() {
        IngestResponse captured = captureOperations.captureHandoff(new CaptureHandoffRequest(
                "codex",
                "codex-id-recall",
                "/tmp/id-recall",
                "next-agent",
                "Completed the queue adapter contract",
                List.of("Board client remains"),
                "Use the linked handoff id for recall"));

        RecallResult recalled = contextService.recall(captured.eventId(), 168, List.of("handoff"));

        assertThat(recalled.scope()).isEqualTo(captured.eventId());
        assertThat(recalled.items()).singleElement().satisfies(item -> {
            assertThat(item.eventId()).isEqualTo(captured.eventId());
            assertThat(item.headline()).isEqualTo("Completed the queue adapter contract");
        });
    }

    @Test
    void projectionRoundTripsThroughRecallAndProjectGraphFeed() {
        String repo = "/tmp/projection-roundtrip";
        IngestResponse captured = captureOperations.captureProjection(new CaptureProjectionRequest(
                "codex",
                "codex-projection",
                repo,
                "Current graph feed can already render projection ghost nodes",
                List.of(
                        new ProjectionPath(
                                "Ship projection capture", "Add MCP and REST capture surfaces for future paths.", 0.78),
                        new ProjectionPath(
                                "Tune trajectory ranking",
                                "Let the frontend pick the latest set and rank ghost futures.",
                                0.51))));

        RecallResult recalled = contextService.recall(repo, 168, List.of("projection"));
        assertThat(recalled.kinds()).containsExactly("projection");
        assertThat(recalled.count()).isEqualTo(1);
        RecalledItem recalledProjection = recalled.items().getFirst();
        assertThat(recalledProjection).satisfies(item -> {
            assertThat(item.eventId()).isEqualTo(captured.eventId());
            assertThat(item.kind()).isEqualTo("projection");
            assertThat(item.headline()).isEqualTo("Ship projection capture");
            assertThat(item.rationale()).isEqualTo("Current graph feed can already render projection ghost nodes");
            assertThat(item.confidence()).isEqualTo(0.78);
            assertThat(item.repo()).isEqualTo(repo);
        });

        ProjectTrajectoryResponse graph = projectGraph.graph(ProjectKey.of(repo).encoded());
        assertThat(graph.canonicalKey()).isEqualTo(repo);
        assertThat(graph.captures())
                .filteredOn(capture -> captured.eventId().equals(capture.id()))
                .singleElement()
                .satisfies(capture -> {
                    assertThat(capture.kind()).isEqualTo("projection");
                    assertThat(capture.headline()).isEqualTo(recalledProjection.headline());
                    assertThat(capture.paths()).hasSize(2);
                    assertThat(capture.paths().getFirst().title()).isEqualTo("Ship projection capture");
                    assertThat(capture.paths().getFirst().description())
                            .isEqualTo("Add MCP and REST capture surfaces for future paths.");
                    assertThat(capture.paths().getFirst().confidence()).isEqualTo(0.78);
                });
    }

    @Test
    void projectionWithoutBasisUsesFirstPathHeadlineInRecall() {
        String repo = "/tmp/projection-no-basis";
        captureOperations.captureProjection(new CaptureProjectionRequest(
                "codex",
                "codex-projection-no-basis",
                repo,
                null,
                List.of(new ProjectionPath("Basis-free path", null, null))));

        RecallResult recalled = contextService.recall(repo, 168, List.of("projection"));

        assertThat(recalled.items()).singleElement().satisfies(item -> {
            assertThat(item.headline()).isEqualTo("Basis-free path");
            assertThat(item.rationale()).isNull();
            assertThat(item.confidence()).isNull();
        });
    }

    @Test
    void projectionPostEndpointValidatesAndStoresPaths() throws Exception {
        String repo = "/tmp/projection-http";
        String response = mockMvc.perform(post("/api/projections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "source": "codex",
                                  "clientSessionId": "codex-projection-http",
                                  "repo": "%s",
                                  "basis": "HTTP endpoint captures structured future paths",
                                  "paths": [
                                    {
                                      "title": "Capture through REST",
                                      "description": "Bind JSON into ProjectionPath records.",
                                      "confidence": 0.72
                                    },
                                    {
                                      "title": "Recall the captured projection"
                                    }
                                  ]
                                }
                                """.formatted(repo)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventType").value("Projection"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode body = objectMapper.readTree(response);
        AgentEvent stored =
                repository.findEventById(body.path("eventId").asText()).orElseThrow();

        assertThat(stored.eventType()).isEqualTo("Projection");
        assertThat(stored.text())
                .contains("1. Capture through REST — Bind JSON into ProjectionPath records.")
                .contains("2. Recall the captured projection");
        assertThat(stored.metadata()).containsEntry("kind", "projection");
        assertThat(stored.metadata().get("paths")).isInstanceOf(List.class);
        List<?> paths = (List<?>) stored.metadata().get("paths");
        assertThat(paths).hasSize(2);
        assertThat(paths.getFirst()).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> first = (Map<String, Object>) paths.getFirst();
        assertThat(first).containsEntry("title", "Capture through REST");
        assertThat(first).containsEntry("description", "Bind JSON into ProjectionPath records.");
        assertThat(first).containsEntry("confidence", 0.72);
        assertThat(paths.getLast()).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> last = (Map<String, Object>) paths.getLast();
        assertThat(last).containsEntry("title", "Recall the captured projection");
        assertThat(last).doesNotContainKeys("description", "confidence");

        mockMvc.perform(post("/api/projections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"source":"codex","clientSessionId":"missing-paths","repo":"%s"}
                                """.formatted(repo)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.type").value("validation_failed"));

        mockMvc.perform(post("/api/projections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"source":"codex","clientSessionId":"empty-paths","repo":"%s","paths":[]}
                                """.formatted(repo)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.type").value("validation_failed"));

        mockMvc.perform(post("/api/projections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "source": "codex",
                                  "clientSessionId": "blank-paths",
                                  "repo": "%s",
                                  "paths": [
                                    {"title": " ", "description": " "},
                                    {"description": "missing title", "confidence": 0.4}
                                  ]
                                }
                                """.formatted(repo)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.type").value("invalid_argument"));
    }

    @Test
    void capturedIntentAlsoLandsAsAnEventOnTheTimeline() {
        captureOperations.captureDecision(new CaptureDecisionRequest(
                "codex",
                "codex-timeline",
                "/tmp/acme-timeline",
                "Pin the SQLite driver version",
                "Reproducible builds",
                List.of(),
                0.9,
                List.of()));

        AgentSession session = repository.findSession("codex", "codex-timeline").orElseThrow();
        List<AgentEvent> events = repository.eventsForSession(session.id(), 10);
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().eventType()).isEqualTo("Decision");
        assertThat(events.getFirst().text()).contains("Pin the SQLite driver version");
    }

    @Test
    void unmatchedScopeRecallsNothing() {
        captureOperations.captureDecision(new CaptureDecisionRequest(
                "codex", "codex-empty", "/tmp/zzz-isolated", "Something", null, null, 0.5, null));

        RecallResult none = contextService.recall("a-topic-that-does-not-exist-anywhere", 168, null);
        assertThat(none.count()).isZero();
        assertThat(none.items()).isEmpty();
    }
}
