package dev.nathan.sbaagentic.judgment.internal.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nathan.sbaagentic.judgment.internal.domain.Beat;
import dev.nathan.sbaagentic.recording.AgentEvent;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BeatFolderTest {

    private final BeatFolder folder = new BeatFolder(new ObjectMapper(), 4_000, 12, 1_500);

    @Test
    void foldsEventsWhileWithinGapAndFlushesAfterQuietWindow() {
        assertThat(folder.fold(event("e1", "PostToolUse", "tool", "one", 0)).closed())
                .isEmpty();
        assertThat(folder.fold(event("e2", "PostToolUse", "tool", "two", 4_000)).closed())
                .isEmpty();

        assertThat(folder.flushQuiet(Instant.parse("2026-09-21T12:00:08.001Z")))
                .singleElement()
                .satisfies(beat -> assertThat(beat.eventIds()).containsExactly("e1", "e2"));
    }

    @Test
    void closesWhenGapExceedsLimit() {
        BeatFolder.FoldResult result = folder.fold(event("e1", "PostToolUse", "tool", "one", 0));
        assertThat(result.closed()).isEmpty();

        result = folder.fold(event("e2", "PostToolUse", "tool", "two", 4_001));

        assertThat(result.closed()).get().extracting(Beat::eventIds).isEqualTo(java.util.List.of("e1"));
    }

    @Test
    void closesBeforeThirteenthEvent() {
        BeatFolder sizeFolder = new BeatFolder(new ObjectMapper(), 4_000, 12, 1_500);
        for (int i = 0; i < 12; i++) {
            assertThat(sizeFolder
                            .fold(event("e" + i, "PostToolUse", "tool", "line", i))
                            .closed())
                    .isEmpty();
        }

        BeatFolder.FoldResult result = sizeFolder.fold(event("e12", "PostToolUse", "tool", "line", 12));

        assertThat(result.closed())
                .get()
                .extracting(beat -> beat.events().size())
                .isEqualTo(12);
    }

    @Test
    void closersCloseTheOpenBeatAndStandAlone() {
        folder.fold(event("e1", "PostToolUse", "tool", "tool churn", 0));

        BeatFolder.FoldResult result = folder.fold(event("e2", "Decision", "assistant", "Choose the smaller slice", 1));

        assertThat(result.closed()).get().extracting(Beat::eventIds).isEqualTo(java.util.List.of("e1"));
        assertThat(result.standalone()).get().satisfies(beat -> {
            assertThat(beat.eventIds()).containsExactly("e2");
            assertThat(beat.text()).contains("Decision: Choose the smaller slice");
        });
    }

    @Test
    void rendersReadablePromptAndToolLines() {
        AgentEvent prompt = event("prompt", "UserPromptSubmit", "user", "Fix this", 0);
        AgentEvent tool = new AgentEvent(
                "tool",
                "s1",
                "codex",
                "c1",
                null,
                "PostToolUse",
                "tool",
                "ignored",
                "exec_command",
                "{\"command\":\"mvn test\"}",
                "{\"stdout\":\"Tests passed\\nsecond\"}",
                Map.of(),
                Instant.parse("2026-09-21T12:00:01Z"));

        assertThat(folder.lineFor(prompt)).isEqualTo("Nathan: Fix this");
        assertThat(folder.lineFor(tool)).isEqualTo("exec_command(mvn test) → Tests passed");
    }

    private static AgentEvent event(String id, String type, String role, String text, long millis) {

        return new AgentEvent(
                id,
                "s1",
                "codex",
                "c1",
                null,
                type,
                role,
                text,
                null,
                null,
                null,
                Map.of(),
                Instant.parse("2026-09-21T12:00:00Z").plusMillis(millis));
    }
}
