package dev.nathan.sbaagentic.judgment.internal.adapter.out.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nathan.sbaagentic.judgment.internal.domain.Beat;
import dev.nathan.sbaagentic.judgment.internal.domain.BeatEvent;
import dev.nathan.sbaagentic.judgment.internal.domain.BeatState;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class JevJudgeTest {

    @Test
    void postsRequestThroughTransportAndValidatesResponse() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        JudgeQuestionSet questions = JudgeQuestionSet.load(objectMapper, new ClassPathResource("judge/questions.json"));
        AtomicReference<String> body = new AtomicReference<>();
        JevTransport transport = (endpoint, apiKey, requestBody, timeout) -> {
            body.set(requestBody);
            assertThat(endpoint).isEqualTo(JevJudge.ENDPOINT);
            assertThat(apiKey).isEqualTo("key");
            assertThat(timeout).isEqualTo(Duration.ofSeconds(12));

            return """
                    {"model":"jev-latest","answers":{
                      "phase":{"type":"choice","choice":"building","confidence":0.9,"probabilities":{"building":0.9}},
                      "salience":{"type":"score","score":1,"confidence":0.8,"probabilities":{"1":0.8}},
                      "novelty":{"type":"score","score":0,"confidence":0.8,"probabilities":{"0":0.8}},
                      "kin_0":{"type":"noul","noul":0.6}
                    }}
                    """;
        };
        JevJudge judge = new JevJudge(
                "key",
                Duration.ofSeconds(12),
                objectMapper,
                transport,
                questions,
                Clock.fixed(Instant.parse("2026-09-21T12:00:00Z"), ZoneOffset.UTC));

        var result = judge.judge(state());

        assertThat(result).isPresent();
        assertThat(result.get().phase()).isEqualTo("building");
        assertThat(body.get()).contains("\"questions\"").contains("\"kin_0\"").doesNotContain("\"human\"");
        assertThat(judge.stats().calls()).isEqualTo(1);
        assertThat(judge.stats().failures()).isZero();
    }

    private static BeatState state() {
        Beat beat = new Beat(
                "b1",
                "s1",
                Instant.parse("2026-09-21T12:00:00Z"),
                Instant.parse("2026-09-21T12:00:00Z"),
                List.of(new BeatEvent(
                        "e1",
                        "s1",
                        "PostToolUse",
                        "tool",
                        "out",
                        "exec",
                        "{}",
                        "{}",
                        Map.of(),
                        Instant.parse("2026-09-21T12:00:00Z"))),
                List.of("exec(mvn test) → passed"),
                "exec(mvn test) → passed");

        return new BeatState(
                beat,
                new BeatState.SessionState("codex", "/repo", "Orbit"),
                List.of(),
                List.of(new BeatState.OtherSessionState("s2", 0, "claude", "/repo", "Other", "latest")),
                false,
                0.0);
    }
}
