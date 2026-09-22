package dev.nathan.sbaagentic.judgment.internal.adapter.out.http;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.nathan.sbaagentic.judgment.internal.domain.Beat;
import dev.nathan.sbaagentic.judgment.internal.domain.BeatEvent;
import dev.nathan.sbaagentic.judgment.internal.domain.BeatState;

import org.junit.jupiter.api.Test;

import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

class JevRequestBuilderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void buildsStateAndQuestionsFromCanonicalResource() throws Exception {
        JudgeQuestionSet spec = JudgeQuestionSet.load(objectMapper, new ClassPathResource("judge/questions.json"));
        BeatState state = state(true);

        var request = new JevRequestBuilder(spec).build(state);

        assertThat(request.path("model").asText()).isEqualTo("jev-latest");
        assertThat(request.at("/state/beat/events/0/line").asText()).isEqualTo("Nathan: build it");
        assertThat(request.at("/state/session/repo").asText()).isEqualTo("~/Developer/proj/sba-agentic");
        assertThat(request.at("/state/trail/0").asText()).isEqualTo("read files");
        assertThat(request.at("/state/others/0/k").asInt()).isZero();
        assertThat(request.at("/state/others/0/latest").asText()).isEqualTo("same feature");
        assertThat(request.path("questions").has("human")).isTrue();
        assertThat(request.path("questions").has("kin_0")).isTrue();
        assertThat(request.at("/questions/kin_0/instructions").asText()).contains("others[0]");
    }

    @Test
    void omitsHumanQuestionWhenCodeOwnsTheRule() throws Exception {
        JudgeQuestionSet spec = JudgeQuestionSet.load(objectMapper, new ClassPathResource("judge/questions.json"));

        var request = new JevRequestBuilder(spec).build(state(false));

        assertThat(request.path("questions").has("human")).isFalse();
        assertThat(request.path("questions").has("phase")).isTrue();
    }

    private static BeatState state(boolean askHuman) {
        Beat beat = new Beat(
                "b1",
                "s1",
                Instant.parse("2026-09-21T12:00:00Z"),
                Instant.parse("2026-09-21T12:00:00Z"),
                List.of(new BeatEvent("e1", "s1", "UserPromptSubmit", "user", "build it", null, null, null, Map.of(),
                        Instant.parse("2026-09-21T12:00:00Z"))),
                List.of("Nathan: build it"),
                "Nathan: build it");
        return new BeatState(
                beat,
                new BeatState.SessionState("codex", "/Users/nathan/Developer/proj/sba-agentic", "Orbit cortex"),
                List.of("read files"),
                List.of(new BeatState.OtherSessionState("s2", 0, "claude", "/repo/other", "Other", "same feature")),
                askHuman,
                askHuman ? Double.NaN : 0.0);
    }
}
