package dev.nathan.sbaagentic.context;

import java.util.List;

import dev.nathan.sbaagentic.event.AgentEvent;
import dev.nathan.sbaagentic.event.EventRepository;
import dev.nathan.sbaagentic.session.AgentSession;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the write+query loop end to end: an agent commits structured intent, and a later recall
 * — scoped by repo or topic — reads that intent back as typed fields, not raw text. This is the
 * behavior that distinguishes Black Box from a read-only timeline, so it earns direct coverage.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite:file:context-loop-test?mode=memory&cache=shared",
        "sba.local-ai.enabled=false",
        "sba.elasticsearch.enabled=false"
})
class ContextLoopTest {

    @Autowired
    ContextService contextService;

    @Autowired
    EventRepository repository;

    @Test
    void decisionRoundTripsAsStructuredIntentRecallableByRepo() {
        contextService.captureDecision(new CaptureDecisionRequest(
                "codex", "codex-1", "/tmp/acme-roundtrip",
                "Use JWT refresh-rotation for auth",
                "Stateless and horizontally scalable",
                List.of("Server-side sessions in Redis"),
                0.8,
                List.of("revoke-on-logout not wired yet")));

        RecallResult byRepo = contextService.recall("/tmp/acme-roundtrip", 168, List.of("decision"));
        assertThat(byRepo.count()).isEqualTo(1);
        RecalledItem item = byRepo.items().getFirst();
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
        contextService.captureDecision(new CaptureDecisionRequest(
                "codex", "codex-topic", "/tmp/other-repo",
                "Adopt structured logging with correlation ids",
                "Traceability across services", List.of(), 0.6, List.of()));

        // The repo does not match, but the topic appears in the decision text.
        RecallResult byTopic = contextService.recall("correlation", 168, List.of("decision"));
        assertThat(byTopic.count()).isEqualTo(1);
        assertThat(byTopic.items().getFirst().headline()).contains("structured logging");
    }

    @Test
    void handoffIsRecalledByDefaultAndCarriesOpenLoops() {
        contextService.captureHandoff(new CaptureHandoffRequest(
                "claude", "claude-7", "/tmp/checkout",
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
    void capturedIntentAlsoLandsAsAnEventOnTheTimeline() {
        contextService.captureDecision(new CaptureDecisionRequest(
                "codex", "codex-timeline", "/tmp/acme-timeline",
                "Pin the SQLite driver version", "Reproducible builds", List.of(), 0.9, List.of()));

        AgentSession session = repository.findSession("codex", "codex-timeline").orElseThrow();
        List<AgentEvent> events = repository.eventsForSession(session.id(), 10);
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().eventType()).isEqualTo("Decision");
        assertThat(events.getFirst().text()).contains("Pin the SQLite driver version");
    }

    @Test
    void unmatchedScopeRecallsNothing() {
        contextService.captureDecision(new CaptureDecisionRequest(
                "codex", "codex-empty", "/tmp/zzz-isolated", "Something", null, null, 0.5, null));

        RecallResult none = contextService.recall("a-topic-that-does-not-exist-anywhere", 168, null);
        assertThat(none.count()).isZero();
        assertThat(none.items()).isEmpty();
    }
}
