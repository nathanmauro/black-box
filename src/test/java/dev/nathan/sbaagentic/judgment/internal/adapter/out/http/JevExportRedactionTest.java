package dev.nathan.sbaagentic.judgment.internal.adapter.out.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nathan.sbaagentic.judgment.internal.domain.BeatState;
import dev.nathan.sbaagentic.recording.IngestionProperties;
import dev.nathan.sbaagentic.recording.internal.application.RedactionService;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;

class JevExportRedactionTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void quotedSecretsAreRemovedFromWireAndTelemetryDespiteDisabledOrCustomIngest(boolean custom) throws Exception {
        var properties = new IngestionProperties();
        if (custom) properties.setRedactPatterns(List.of("INTERNAL-[0-9]{4}"));
        else properties.setRedactEnabled(false);
        var redactor = new RedactionService(properties);
        var mapper = new ObjectMapper();
        for (String secret : new String[] {
            "api_key=FAKE_SENTINEL_123",
            "password=\"FAKE_SENTINEL_123 with spaces\"",
            "password='FAKE_SENTINEL_123 with \\'quotes\\''",
            "password=F4KE7",
            "{\"token\":\"F4KE7\"}",
            "{\"api_key\":\"FAKE_SENTINEL_123 with \\\"quotes\\\"\"}",
            "{\"token\":\"FAKE_SENTINEL_123\"}",
            "tool({\\\"api_key\\\":\\\"FAKE_SENTINEL_123\\\"})",
            "token=[REDACTED].[REDACTED].FAKE_SENTINEL_123 neighboring=evidence",
            "Bearer abcdefghijklmnopqrstuvwxyz"
        }) {
            assertThat(redactor.redact(secret)).isEqualTo(secret);
            var original = JevJudgeTest.state();
            var state = new BeatState(
                    original.beat(),
                    new BeatState.SessionState("token=FAKE_SENTINEL_123", "/repo", secret),
                    original.trail(),
                    original.others(),
                    original.askHuman(),
                    original.ruleHuman());
            var rows = new ArrayList<Map<String, Object>>();
            var judge = new JevJudge(
                    "provider-credential",
                    Duration.ofSeconds(1),
                    mapper,
                    (url, key, body, timeout) -> {
                        assertThat(body).doesNotContain("FAKE_SENTINEL_123", "abcdefghijklmnopqrstuvwxyz", "F4KE7");
                        assertThat(rows.get(0).get("request_body")).isEqualTo(body);

                        return JevTelemetryTest.RESPONSE.replace("jev-test", "token=FAKE_SENTINEL_123");
                    },
                    JudgeQuestionSet.load(mapper, new ClassPathResource("judge/questions.json")),
                    Clock.systemUTC(),
                    new JevTelemetry(true, rows::add),
                    redactor::redactForExport);
            assertThat(judge.judge(state)).isPresent();
            assertThat(rows.get(0)).containsEntry("source", "other");
            assertThat(rows.get(1)).containsEntry("model", "unknown");
            assertThat(mapper.writeValueAsString(rows))
                    .doesNotContain("FAKE_SENTINEL_123", "abcdefghijklmnopqrstuvwxyz", "F4KE7");
        }
    }
}
