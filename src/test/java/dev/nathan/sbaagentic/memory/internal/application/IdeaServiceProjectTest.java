package dev.nathan.sbaagentic.memory.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import dev.nathan.sbaagentic.memory.internal.application.port.IdeaEventReader;
import dev.nathan.sbaagentic.memory.internal.application.port.IdeaEventReader.TypedEvent;
import dev.nathan.sbaagentic.recording.AgentEvent;
import dev.nathan.sbaagentic.recording.RecordingCaptureOperations;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class IdeaServiceProjectTest {
    static Stream<Arguments> projectAttribution() {

        return Stream.of(
                Arguments.of(null, null, "/fixture/older", "/fixture/older-cwd", "/fixture/older"),
                Arguments.of(" \t", " ", "/fixture/older", null, "/fixture/older"),
                Arguments.of(null, null, null, "/fixture/older-cwd", "/fixture/older-cwd"),
                Arguments.of(null, null, " ", "/fixture/older-cwd", "/fixture/older-cwd"),
                Arguments.of("/fixture/newer", "/fixture/newer-cwd", "/fixture/older", null, "/fixture/newer"),
                Arguments.of(null, "/fixture/newer-cwd", "/fixture/older", null, "/fixture/newer-cwd"),
                Arguments.of(" ", "/fixture/newer-cwd", "/fixture/older", null, "/fixture/newer-cwd"),
                Arguments.of(null, null, null, null, null),
                Arguments.of(" ", "\t", "\n", " ", null));
    }

    @ParameterizedTest
    @MethodSource("projectAttribution")
    void usesNewestEffectiveProjectWithoutReplacingLatestRevisionIdentity(
            String latestRepo, String latestCwd, String olderRepo, String olderCwd, String expected) {
        TypedEvent older = row("older", olderRepo, olderCwd, "2026-01-01T00:00:00Z");
        TypedEvent latest = row("latest", latestRepo, latestCwd, "2026-01-01T00:00:00.000000001Z");
        IdeaEventReader reader = (eventType, prefix, before, limit) -> List.of(older, latest);
        RecordingCaptureOperations capture = mock(RecordingCaptureOperations.class);
        IdeaService service = new IdeaService(reader, capture, scope -> List.of());

        IdeaListResponse listed = service.list(null);

        assertThat(listed.items()).singleElement().satisfies(idea -> {
            assertThat(idea.repo()).isEqualTo(expected);
            assertThat(idea.eventId()).isEqualTo("latest");
            assertThat(idea.sessionId()).isEqualTo("session-latest");
            assertThat(idea.clientSessionId()).isEqualTo("client-latest");
            assertThat(idea.title()).isEqualTo("Latest title");
            assertThat(idea.status()).isEqualTo("tracked");
            assertThat(idea.revisions()).isEqualTo(2);
            assertThat(idea.capturedAt()).isEqualTo(latest.event().observedAt());
            assertThat(idea.firstCapturedAt()).isEqualTo(older.event().observedAt());
        });
        verifyNoInteractions(capture);
    }

    private TypedEvent row(String id, String repo, String cwd, String observedAt) {
        Map<String, Object> metadata = new LinkedHashMap<>(Map.of(
                "ideaKey", "stable-key", "title", "Latest title", "status", "tracked", "origin", "agent-proposed"));
        if (repo != null) metadata.put("repo", repo);

        return new TypedEvent(
                new AgentEvent(
                        id,
                        "session-" + id,
                        "manual",
                        "client-" + id,
                        null,
                        "Idea",
                        "assistant",
                        "[Idea] Fixture",
                        null,
                        null,
                        null,
                        metadata,
                        Instant.parse(observedAt)),
                cwd);
    }
}
