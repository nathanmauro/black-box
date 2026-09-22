package dev.nathan.sbaagentic.judgment.internal.adapter.out.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class JevTelemetryTest {
    final ObjectMapper mapper = new ObjectMapper();
    static final String RESPONSE = """
        {"model":"jev-test","answers":{
          "phase":{"type":"choice","choice":"building","confidence":0.9,"probabilities":{"building":0.9}},
          "salience":{"type":"score","score":1,"confidence":0.8,"probabilities":{"1":0.8}},
          "novelty":{"type":"score","score":0,"confidence":0.8,"probabilities":{"0":0.8}},
          "kin_0":{"type":"noul","noul":0.6}}}
        """;

    JevJudge judge(JevTelemetry telemetry, JevTransport transport) throws Exception {

        return new JevJudge(
                "provider-credential",
                Duration.ofSeconds(1),
                mapper,
                transport,
                JudgeQuestionSet.load(mapper, new ClassPathResource("judge/questions.json")),
                Clock.systemUTC(),
                telemetry,
                text -> text.replace("Orbit", "[REDACTED]"));
    }

    @Test
    void exactSanitizedWireBodyAndValidatedResultShareCorrelation() throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        AtomicReference<String> wire = new AtomicReference<>();
        var result = judge(new JevTelemetry(true, rows::add), (url, key, body, timeout) -> {
                    wire.set(body);

                    return RESPONSE;
                })
                .judge(JevJudgeTest.state());
        assertThat(result).isPresent();
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("request_body")).isEqualTo(wire.get());
        assertThat(wire.get()).contains("[REDACTED]").doesNotContain("Orbit", "provider-credential");
        assertThat(rows.get(0).get("request_body_truncated")).isEqualTo(false);
        assertThat(rows.get(0).get("request_id")).isEqualTo(rows.get(1).get("request_id"));
        assertThat(rows.get(1)).containsEntry("phase", "building").containsEntry("outcome", "success");
        assertThat(rows.get(0).get("event_ids")).isEqualTo(List.of("e1"));
        assertThat(rows.get(0).get("request_body_sha256"))
                .isEqualTo(java.util.HexFormat.of()
                        .formatHex(java.security.MessageDigest.getInstance("SHA-256")
                                .digest(wire.get().getBytes(java.nio.charset.StandardCharsets.UTF_8))));
    }

    @Test
    void disabledAndBrokenTelemetryCannotSuppressJudging() throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        assertThat(judge(new JevTelemetry(false, rows::add), (u, k, b, t) -> RESPONSE)
                        .judge(JevJudgeTest.state()))
                .isPresent();
        assertThat(rows).isEmpty();
        assertThat(judge(
                                new JevTelemetry(true, fields -> {
                                    throw new IllegalStateException();
                                }),
                                (u, k, b, t) -> RESPONSE)
                        .judge(JevJudgeTest.state()))
                .isPresent();
    }

    @Test
    void failuresAreCorrelatedWithoutExceptionMessagesOrRawResponses() throws Exception {
        for (boolean malformed : List.of(false, true)) {
            List<Map<String, Object>> rows = new ArrayList<>();
            assertThat(judge(new JevTelemetry(true, rows::add), (u, k, b, t) -> {
                                if (malformed)

                                    return "SECRET_RESPONSE";
                                throw new java.net.http.HttpTimeoutException("SECRET_EXCEPTION");
                            })
                            .judge(JevJudgeTest.state()))
                    .isEmpty();
            assertThat(rows).hasSize(2);
            assertThat(rows.get(1)).containsEntry("outcome", "error");
            assertThat(rows.get(1).get("request_id")).isEqualTo(rows.get(0).get("request_id"));
            assertThat(mapper.writeValueAsString(rows))
                    .doesNotContain("SECRET_RESPONSE", "SECRET_EXCEPTION", "provider-credential");
        }
    }

    @Test
    void boundedBodyMarksTruncationAndKnownCredentialOmission() {
        List<Map<String, Object>> rows = new ArrayList<>();
        var telemetry = new JevTelemetry(true, rows::add);
        String body = "x".repeat(JevTelemetry.MAX_BODY_CHARS + 1);
        telemetry.requested("r", JevJudgeTest.state(), Instant.now(), body, "credential");
        assertThat(rows.get(0)).containsEntry("request_body_truncated", true);
        assertThat(rows.get(0).get("request_body")).isEqualTo(body.substring(0, JevTelemetry.MAX_BODY_CHARS));
        telemetry.requested("s", JevJudgeTest.state(), Instant.now(), "has credential", "credential");
        assertThat(rows.get(1)).containsEntry("request_body_omitted", true).doesNotContainKey("request_body");
    }
}
