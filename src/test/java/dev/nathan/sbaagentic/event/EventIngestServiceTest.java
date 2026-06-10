package dev.nathan.sbaagentic.event;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.awaitility.Awaitility.await;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite:file:event-ingest-test?mode=memory&cache=shared",
        "sba.local-ai.enabled=false",
        "sba.summary.backend=local",
        "sba.elasticsearch.enabled=false"
})
class EventIngestServiceTest {

    @Autowired
    EventIngestService ingestService;

    @Autowired
    EventRepository repository;

    @Test
    void ingestCreatesSessionAndSearchableEvent() {
        IngestResponse response = ingestService.ingest(new EventIngestRequest(
                "Claude",
                "session-1",
                "turn-1",
                "UserPromptSubmit",
                "user",
                "Wire the local agent hook into the Spring Boot app.",
                "/tmp/project",
                null,
                null,
                null,
                Map.of("title", "Hook wiring"),
                Instant.parse("2026-05-21T12:00:00Z")));

        assertThat(response.source()).isEqualTo("claude");
        assertThat(repository.findSession("claude", "session-1").orElseThrow().eventCount()).isEqualTo(1);
        assertThat(repository.searchEvents("Spring Boot", 10))
                .extracting(AgentEvent::id)
                .contains(response.eventId());
    }

    @Test
    void finalLifecycleEventSchedulesBlackBoxOwnedSummary() {
        ingestService.ingest(new EventIngestRequest(
                "codex",
                "session-final",
                "turn-1",
                "UserPromptSubmit",
                "user",
                "Black Box should summarize this after finalization.",
                "/tmp/project",
                null,
                null,
                null,
                Map.of("title", "Final summary ownership"),
                Instant.parse("2026-05-21T12:00:00Z")));

        ingestService.ingest(new EventIngestRequest(
                "codex",
                "session-final",
                "turn-2",
                "Stop",
                "agent",
                "Final lifecycle event reached Black Box.",
                "/tmp/project",
                null,
                null,
                null,
                Map.of(),
                Instant.parse("2026-05-21T12:01:00Z")));

        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(
                repository.findSession("codex", "session-final").orElseThrow().summary())
                .contains("Black Box should summarize this after finalization.")
                .contains("[Stop] Final lifecycle event reached Black Box."));
    }

    @Test
    void ingestRedactsSecretsBeforePersistenceAndTitleDerivation() {
        IngestResponse response = ingestService.ingest(new EventIngestRequest(
                "codex",
                "session-redaction",
                "turn-1",
                "UserPromptSubmit",
                "user",
                "password=supersecretvalue\nUse the captured tool output.",
                "/tmp/project",
                "shell",
                Map.of("command", "Authorization: Bearer sk-proj-abc123def456ghi789jkl"),
                Map.of(
                        "stdout", "ghp_abcdefghijklmnopqrstuvwxyz0123456789AB",
                        "nested", Map.of("list", List.of("xoxb-1234567890-abcdefghij"))),
                Map.of("rawHook", Map.of("prompt", "api_key=abcdefghi")),
                Instant.parse("2026-05-21T12:02:00Z")));

        AgentEvent event = repository.eventsForSession(response.sessionId(), 10).stream()
                .filter(candidate -> candidate.id().equals(response.eventId()))
                .findFirst()
                .orElseThrow();

        assertThat(event.text()).isEqualTo("password=[REDACTED]\nUse the captured tool output.");
        assertThat(event.toolInputJson())
                .contains("\"command\":\"Authorization: Bearer [REDACTED]\"")
                .doesNotContain("sk-proj");
        assertThat(event.toolOutputJson())
                .contains("[REDACTED]")
                .doesNotContain("ghp_")
                .doesNotContain("xoxb-");
        assertThat(event.metadata().toString())
                .contains("[REDACTED]")
                .doesNotContain("abcdefghi");
        assertThat(repository.findSession("codex", "session-redaction").orElseThrow().title())
                .isEqualTo("password=[REDACTED]");
    }
}
