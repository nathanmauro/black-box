package dev.nathan.sbaagentic.recording.internal.adapter.out.file;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nathan.sbaagentic.recording.AgentSession;
import dev.nathan.sbaagentic.recording.IngestionProperties;
import dev.nathan.sbaagentic.recording.TranscriptProperties;
import dev.nathan.sbaagentic.recording.internal.application.RedactionService;
import dev.nathan.sbaagentic.recording.internal.application.port.TranscriptRead;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JsonlTranscriptMessageSourceTest {

    @TempDir
    Path tempDir;

    @Test
    void readsCodexMessagesDeduplicatesFallbacksAndRefreshesAfterAppend() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("codex"));
        Path transcript = root.resolve("codex-session.jsonl");
        Files.writeString(transcript, """
                {"timestamp":"2026-08-30T12:00:00Z","type":"session_meta","payload":{"id":"codex-session","session_id":"codex-session"}}
                {"timestamp":"2026-08-30T12:00:01Z","type":"turn_context","payload":{"turn_id":"turn-1"}}
                {"timestamp":"2026-08-30T12:00:02Z","type":"response_item","payload":{"type":"message","id":"injected-1","role":"user","content":[{"type":"input_text","text":"# AGENTS.md instructions"},{"type":"input_text","text":"<environment_context>hidden</environment_context>"}]}}
                {"timestamp":"2026-08-30T12:00:02Z","type":"event_msg","payload":{"type":"user_message","message":"Show every response"}}
                {"timestamp":"2026-08-30T12:00:03Z","type":"event_msg","payload":{"type":"agent_message","message":"Complete answer"}}
                {"timestamp":"2026-08-30T12:00:04Z","type":"response_item","payload":{"type":"message","id":"assistant-1","role":"assistant","content":[{"type":"output_text","text":"Complete answer"}]}}
                {"timestamp":"2026-08-30T12:00:05Z","type":"response_item","payload":{"type":"message","id":"developer-1","role":"developer","content":[{"type":"input_text","text":"hidden policy"}]}}
                not-json
                """);
        JsonlTranscriptMessageSource source = source(root);
        AgentSession session = session("codex", "codex-session");

        TranscriptRead first = source.read(session, List.of(transcript.toString()));

        assertThat(first.available()).isTrue();
        assertThat(first.complete()).isFalse();
        assertThat(first.reason()).contains("1 malformed");
        assertThat(first.messages())
                .extracting(event -> event.role() + ":" + event.text())
                .containsExactly("user:Show every response", "assistant:Complete answer");
        assertThat(first.messages()).allSatisfy(event -> {
            assertThat(event.id()).startsWith("tx:codex:");
            assertThat(event.metadata()).containsEntry("transcript", true);
        });

        Files.writeString(transcript, """
                {"timestamp":"2026-08-30T12:00:06Z","type":"response_item","payload":{"type":"message","id":"assistant-2","role":"assistant","content":[{"type":"output_text","text":"Appended answer"}]}}
                """, StandardOpenOption.APPEND);

        TranscriptRead refreshed = source.read(session, List.of(transcript.toString()));
        assertThat(refreshed.messages())
                .extracting(event -> event.text())
                .containsExactly("Show every response", "Complete answer", "Appended answer");
    }

    @Test
    void readsClaudeTextButNotThinkingOrToolResultsAndRedactsSecrets() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("claude"));
        Path transcript = root.resolve("claude-session.jsonl");
        Files.writeString(transcript, """
                {"type":"user","sessionId":"claude-session","uuid":"user-1","promptId":"prompt-1","timestamp":"2026-08-30T13:00:00Z","message":{"role":"user","content":[{"type":"text","text":"Inspect the session"}]}}
                {"type":"assistant","sessionId":"claude-session","uuid":"think-1","timestamp":"2026-08-30T13:00:01Z","message":{"role":"assistant","content":[{"type":"thinking","thinking":"private reasoning"}]}}
                {"type":"assistant","sessionId":"claude-session","uuid":"assistant-1","timestamp":"2026-08-30T13:00:02Z","message":{"role":"assistant","content":[{"type":"text","text":"api_key=123456789"},{"type":"tool_use","id":"tool-1","name":"Read","input":{}}]}}
                {"type":"user","sessionId":"claude-session","uuid":"tool-result-1","timestamp":"2026-08-30T13:00:03Z","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"tool-1","content":"result"}]}}
                """);

        TranscriptRead result = source(root).read(session("claude", "claude-session"), List.of(transcript.toString()));

        assertThat(result.available()).isTrue();
        assertThat(result.complete()).isTrue();
        assertThat(result.messages())
                .extracting(event -> event.role() + ":" + event.text())
                .containsExactly("user:Inspect the session", "assistant:api_key=[REDACTED]");
    }

    @Test
    void rejectsPathsOutsideRootsAndMismatchedSessionIdentity() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("allowed"));
        Path outside = tempDir.resolve("outside-session.jsonl");
        Files.writeString(outside, """
                {"timestamp":"2026-08-30T12:00:00Z","type":"session_meta","payload":{"id":"outside-session"}}
                """);
        Path mismatch = root.resolve("different-session.jsonl");
        Files.writeString(mismatch, """
                {"timestamp":"2026-08-30T12:00:00Z","type":"session_meta","payload":{"id":"different-session"}}
                """);
        Path symlink = root.resolve("outside-session.jsonl");
        Files.createSymbolicLink(symlink, outside);
        JsonlTranscriptMessageSource source = source(root);

        assertThat(source.read(session("codex", "outside-session"), List.of(outside.toString()))
                        .available())
                .isFalse();
        assertThat(source.read(session("codex", "outside-session"), List.of(symlink.toString()))
                        .available())
                .isFalse();
        TranscriptRead mismatchResult = source.read(session("codex", "expected-session"), List.of(mismatch.toString()));
        assertThat(mismatchResult.available()).isFalse();
        assertThat(mismatchResult.reason()).isEqualTo("identity-mismatch");
        assertThat(source.read(session("shadow-codex", "different-session"), List.of(mismatch.toString())))
                .extracting(TranscriptRead::available, TranscriptRead::reason)
                .containsExactly(false, "unsupported-source");
    }

    @Test
    void validatesClaudeAndCodexChildTranscriptIdentityWithoutAcceptingSiblings() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("children"));
        Path claudeDir = Files.createDirectories(root.resolve("parent-session/subagents"));
        Path claudeChild = claudeDir.resolve("agent-agent-one.jsonl");
        Files.writeString(claudeChild, """
                {"type":"assistant","sessionId":"parent-session","agentId":"agent-one","uuid":"answer-1","timestamp":"2026-08-30T13:00:00Z","message":{"role":"assistant","content":[{"type":"text","text":"Child answer"}]}}
                """);
        Path codexChild = root.resolve("child-session.jsonl");
        Files.writeString(codexChild, """
                {"timestamp":"2026-08-30T12:00:00Z","type":"session_meta","payload":{"id":"child-session","session_id":"parent-session","parent_thread_id":"parent-session"}}
                {"timestamp":"2026-08-30T12:00:01Z","type":"response_item","payload":{"type":"message","id":"answer-2","role":"assistant","content":[{"type":"output_text","text":"Codex child answer"}]}}
                """);
        JsonlTranscriptMessageSource source = source(root);

        assertThat(source.read(session("claude", "parent-session:agent-one"), List.of(claudeChild.toString()))
                        .messages())
                .extracting(event -> event.text())
                .containsExactly("Child answer");
        assertThat(source.read(session("claude", "parent-session:agent-two"), List.of(claudeChild.toString()))
                        .available())
                .isFalse();
        assertThat(source.read(session("codex", "child-session"), List.of(codexChild.toString()))
                        .messages())
                .extracting(event -> event.text())
                .containsExactly("Codex child answer");
    }

    @Test
    void reportsClippedAndInvalidTimestampMessagesAsIncomplete() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("incomplete"));
        Path transcript = root.resolve("codex-session.jsonl");
        Files.writeString(transcript, """
                {"timestamp":"2026-08-30T12:00:00Z","type":"session_meta","payload":{"id":"codex-session"}}
                {"timestamp":"not-an-instant","type":"response_item","payload":{"type":"message","id":"bad-time","role":"assistant","content":[{"type":"output_text","text":"Dropped answer"}]}}
                {"timestamp":"2026-08-30T12:00:01Z","type":"response_item","payload":{"type":"message","id":"large","role":"assistant","content":[{"type":"output_text","text":"%s"}]}}
                """.formatted("x".repeat(50_001)));

        TranscriptRead result = source(root).read(session("codex", "codex-session"), List.of(transcript.toString()));

        assertThat(result.available()).isTrue();
        assertThat(result.complete()).isFalse();
        assertThat(result.reason()).contains("no valid timestamp", "oversized message");
        assertThat(result.messages()).singleElement().satisfies(message -> {
            assertThat(message.text()).endsWith(" …[truncated]");
            assertThat(message.text()).doesNotContain("Dropped answer");
        });
    }

    @Test
    void cacheKeepsCodexAndClaudeParsersIsolatedForTheSameConfiguredPath() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("shared-root"));
        Path transcript = root.resolve("shared-session.jsonl");
        Files.writeString(transcript, """
                {"timestamp":"2026-08-30T12:00:00Z","type":"session_meta","payload":{"id":"shared-session"}}
                {"timestamp":"2026-08-30T12:00:01Z","type":"response_item","payload":{"type":"message","id":"codex-answer","role":"assistant","content":[{"type":"output_text","text":"Codex answer"}]}}
                {"type":"assistant","sessionId":"shared-session","uuid":"claude-answer","timestamp":"2026-08-30T12:00:02Z","message":{"role":"assistant","content":[{"type":"text","text":"Claude answer"}]}}
                """);
        JsonlTranscriptMessageSource source = source(root);

        assertThat(source.read(session("codex", "shared-session"), List.of(transcript.toString()))
                        .messages())
                .extracting(event -> event.text())
                .containsExactly("Codex answer");
        assertThat(source.read(session("claude", "shared-session"), List.of(transcript.toString()))
                        .messages())
                .extracting(event -> event.text())
                .containsExactly("Claude answer");
    }

    private JsonlTranscriptMessageSource source(Path root) {
        TranscriptProperties transcriptProperties = new TranscriptProperties();
        transcriptProperties.setCodexRoots(List.of(root.toString()));
        transcriptProperties.setClaudeRoots(List.of(root.toString()));
        transcriptProperties.setCacheEntries(2);
        IngestionProperties ingestion = new IngestionProperties();

        return new JsonlTranscriptMessageSource(
                new ObjectMapper(), new RedactionService(ingestion), transcriptProperties);
    }

    private static AgentSession session(String source, String clientSessionId) {

        return new AgentSession(
                "server-session",
                source,
                clientSessionId,
                "Session",
                "/tmp/project",
                null,
                Instant.parse("2026-08-30T12:00:00Z"),
                Instant.parse("2026-08-30T13:00:00Z"),
                1,
                null);
    }
}
