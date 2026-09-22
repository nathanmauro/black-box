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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JevAnswerValidatorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void validatesCompleteAnswerSet() throws Exception {
        JevAnswerValidator validator = validator();

        var judgment = validator.validate(objectMapper.readTree(response("building", true)), state(true),
                Instant.parse("2026-09-21T12:00:01Z"), 12L);

        assertThat(judgment.phase()).isEqualTo("building");
        assertThat(judgment.human()).isEqualTo(0.8);
        assertThat(judgment.kin()).containsEntry("s2", 0.7);
    }

    @Test
    void rejectsUnknownPhaseAndMissingKinWhole() throws Exception {
        JevAnswerValidator validator = validator();

        assertThatThrownBy(() -> validator.validate(objectMapper.readTree(response("shipping", true)), state(true),
                Instant.now(), 1L))
                .isInstanceOf(MalformedJudgmentException.class);
        assertThatThrownBy(() -> validator.validate(objectMapper.readTree(response("building", false)), state(true),
                Instant.now(), 1L))
                .isInstanceOf(MalformedJudgmentException.class);
    }

    @Test
    void usesRuleHumanWhenHumanWasNotAsked() throws Exception {
        JevAnswerValidator validator = validator();

        var judgment = validator.validate(objectMapper.readTree(response("verifying", true)), state(false),
                Instant.now(), 1L);

        assertThat(judgment.human()).isZero();
    }

    private JevAnswerValidator validator() throws Exception {
        return new JevAnswerValidator(JudgeQuestionSet.load(objectMapper, new ClassPathResource("judge/questions.json")));
    }

    private static String response(String phase, boolean includeKin) {
        return """
                {
                  "model": "jev-latest",
                  "answers": {
                    "phase": {"type":"choice","choice":"%s","confidence":0.9,"probabilities":{"building":0.9}},
                    "salience": {"type":"score","score":1.4,"confidence":0.8,"probabilities":{"0":0.1,"1":0.4,"2":0.5}},
                    "novelty": {"type":"score","score":0.5,"confidence":0.7,"probabilities":{"0":0.6,"1":0.3,"2":0.1}},
                    "human": {"type":"noul","noul":0.8}%s
                  }
                }
                """.formatted(phase, includeKin ? ", \"kin_0\": {\"type\":\"noul\",\"noul\":0.7}" : "");
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
                new BeatState.SessionState("codex", "/repo", "Orbit"),
                List.of(),
                List.of(new BeatState.OtherSessionState("s2", 0, "claude", "/repo", "Other", "latest")),
                askHuman,
                askHuman ? Double.NaN : 0.0);
    }
}
