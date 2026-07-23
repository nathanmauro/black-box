package dev.nathan.sbaagentic.recording;

import dev.nathan.sbaagentic.recording.internal.adapter.out.sqlite.RecordingSqlStore;
import dev.nathan.sbaagentic.memory.MemoryEventReader;

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
    EventRecorder ingestService;

    @Autowired
    RecordingSqlStore repository;

    @Autowired
    MemoryEventReader memory;

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
        assertThat(memory.searchEvents("Spring Boot", 10))
                .extracting(AgentEvent::id)
                .contains(response.eventId());
    }

    @Test
    void genericAgentRoleIsDerivedFromNormalizedHookEventTypes() {
        assertThat(ingestAndRead("role-user-prompt", "UserPromptSubmit", "agent", "First prompt").role())
                .isEqualTo("user");
        assertThat(ingestAndRead("role-before-prompt", "before_submit_prompt", "AGENT", "Second prompt").role())
                .isEqualTo("user");
        assertThat(ingestAndRead("role-assistant-message", "assistant-message", "agent", "Answer").role())
                .isEqualTo("assistant");
        assertThat(ingestAndRead("role-stop", "s-top", "agent", "Finished").role())
                .isEqualTo("assistant");
        assertThat(ingestAndRead("role-pre-tool", "pre_tool_use", "agent", null).role())
                .isEqualTo("tool");
        assertThat(ingestAndRead("role-post-tool", "PostToolUse", "agent", null).role())
                .isEqualTo("tool");
        assertThat(ingestAndRead("role-empty-stop", "s.top", "agent", "   ").role())
                .isEqualTo("agent");
        assertThat(ingestAndRead("role-unknown", "CustomHook", "agent", "Unknown event").role())
                .isEqualTo("agent");
    }

    @Test
    void explicitSemanticRolesArePreserved() {
        assertThat(ingestAndRead("explicit-assistant", "UserPromptSubmit", "assistant", "Archive response").role())
                .isEqualTo("assistant");
        assertThat(ingestAndRead("explicit-user", "AssistantMessage", "user", "Archive prompt").role())
                .isEqualTo("user");
        assertThat(ingestAndRead("explicit-tool", "s-top", "tool", "Tool result").role())
                .isEqualTo("tool");
        assertThat(ingestAndRead("explicit-note", "PostToolUse", "note", "Saved observation").role())
                .isEqualTo("note");
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

    private AgentEvent ingestAndRead(String clientSessionId, String eventType, String role, String text) {
        IngestResponse response = ingestService.ingest(new EventIngestRequest(
                "codex",
                clientSessionId,
                "turn-1",
                eventType,
                role,
                text,
                "/tmp/project",
                null,
                null,
                null,
                Map.of(),
                Instant.parse("2026-05-21T12:03:00Z")));

        return repository.eventsForSession(response.sessionId(), 10).stream()
                .filter(candidate -> candidate.id().equals(response.eventId()))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void subagentStartMintsChildSessionStampedWithParentAndAgentTypeTitle() {
        ingestService.ingest(new EventIngestRequest(
                "claude",
                "parent-1:agent-abc",
                null,
                "SubagentStart",
                "agent",
                null,
                "/tmp/project",
                null,
                null,
                null,
                Map.of(
                        "agentId", "agent-abc",
                        "agentType", "code-reviewer",
                        "parentClientSessionId", "parent-1"),
                Instant.parse("2026-07-22T12:00:00Z")));

        AgentSession child = repository.findSession("claude", "parent-1:agent-abc").orElseThrow();
        assertThat(child.spawnedBy()).isEqualTo("parent-1");
        assertThat(child.title()).isEqualTo("code-reviewer");
    }

    @Test
    void subagentStopAloneMintsChildAndLaterEventsNeverClearSpawnedBy() {
        ingestService.ingest(new EventIngestRequest(
                "claude",
                "parent-2:agent-def",
                null,
                "SubagentStop",
                "assistant",
                "Reviewed the diff and found two issues.",
                "/tmp/project",
                null,
                null,
                null,
                Map.of(
                        "agentId", "agent-def",
                        "agentType", "code-reviewer",
                        "parentClientSessionId", "parent-2"),
                Instant.parse("2026-07-22T12:01:00Z")));
        ingestService.ingest(new EventIngestRequest(
                "claude",
                "parent-2:agent-def",
                null,
                "PostToolUse",
                "tool",
                null,
                "/tmp/project",
                "shell",
                null,
                null,
                Map.of(),
                Instant.parse("2026-07-22T12:02:00Z")));

        assertThat(repository.findSession("claude", "parent-2:agent-def").orElseThrow().spawnedBy())
                .isEqualTo("parent-2");
    }

    @Test
    void ordinaryEventsWithoutSubagentMetadataStayUnspawned() {
        ingestService.ingest(new EventIngestRequest(
                "claude",
                "plain-parent",
                null,
                "UserPromptSubmit",
                "user",
                "Hello",
                "/tmp/project",
                null,
                null,
                null,
                Map.of(),
                Instant.parse("2026-07-22T12:03:00Z")));

        assertThat(repository.findSession("claude", "plain-parent").orElseThrow().spawnedBy()).isNull();
    }

    @Test
    void recentSessionsHidesChildrenByDefaultAndRevealsThemOnRequest() {
        ingestService.ingest(new EventIngestRequest(
                "claude",
                "list-parent",
                null,
                "UserPromptSubmit",
                "user",
                "Parent prompt",
                "/tmp/project",
                null,
                null,
                null,
                Map.of(),
                Instant.parse("2026-07-22T14:00:00Z")));
        ingestService.ingest(new EventIngestRequest(
                "claude",
                "list-parent:agent-9",
                null,
                "SubagentStart",
                "agent",
                null,
                "/tmp/project",
                null,
                null,
                null,
                Map.of(
                        "agentId", "agent-9",
                        "agentType", "explorer",
                        "parentClientSessionId", "list-parent"),
                Instant.parse("2026-07-22T14:00:01Z")));

        // The one-arg overload is what the MCP recentSessions tool and the CLI call: parents only.
        assertThat(repository.recentSessions(50))
                .extracting(AgentSession::clientSessionId)
                .contains("list-parent")
                .doesNotContain("list-parent:agent-9");
        assertThat(repository.recentSessions(50, true))
                .extracting(AgentSession::clientSessionId)
                .contains("list-parent", "list-parent:agent-9");
    }
}
