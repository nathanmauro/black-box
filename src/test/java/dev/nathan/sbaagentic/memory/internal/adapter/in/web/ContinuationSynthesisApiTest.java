package dev.nathan.sbaagentic.memory.internal.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nathan.sbaagentic.memory.internal.domain.ContinuationSynthesisPrompt;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** Synthesis enabled against a loopback fake model: one call per POST, whole-reply validation, fixed reasons. */
@SpringBootTest(
        properties = {
            "spring.datasource.url=jdbc:sqlite:${java.io.tmpdir}/bb-continuation-synthesis-on-${random.uuid}.db",
            "sba.continuation.synthesis.enabled=true",
            "sba.local-ai.enabled=true",
            "sba.local-ai.timeout=1s",
            "sba.summary.backend=local",
            "sba.elasticsearch.enabled=false",
            "sba.memory.embedding.enabled=false",
            "sba.ask.embedding-enabled=false",
            "sba.judge.enabled=false"
        })
@AutoConfigureMockMvc
class ContinuationSynthesisApiTest {

    static ContinuationSynthesisFixture model;

    @DynamicPropertySource
    static void localModel(DynamicPropertyRegistry registry) throws Exception {
        model = new ContinuationSynthesisFixture();
        registry.add("sba.local-ai.base-url", model::baseUrl);
    }

    @AfterAll
    static void stopModel() {
        model.stop();
    }

    @Autowired
    MockMvc http;

    @Autowired
    ObjectMapper mapper;

    @BeforeEach
    void reset() {
        model.requests.clear();
    }

    @Test
    void aValidReplyIsReturnedBesideTheUnchangedDeterministicPrompt() throws Exception {
        String project = ContinuationSynthesisFixture.project(http, mapper, "Reproduce the recovery path");
        JsonNode built = ContinuationSynthesisFixture.continuations(http, mapper, project);
        assertThat(built.path("synthesisAvailable").asBoolean()).isTrue();
        assertThat(model.requests).as("GET never calls a model").isEmpty();
        JsonNode candidate = built.path("candidates").path(0);
        String eventId = candidate.path("evidence").path(0).path("eventId").asText();
        model.reply.set(new ContinuationSynthesisFixture.Reply(
                200,
                ContinuationSynthesisFixture.completion(
                        "Reproduce the recovery path [E1]; storage was replaced [E1].", "stop"),
                0));

        JsonNode restated = ContinuationSynthesisFixture.synthesize(
                http,
                mapper,
                Map.of(
                        "project",
                        project,
                        "withinHours",
                        8760,
                        "candidateId",
                        candidate.path("id").asText()),
                status().isOk());

        assertThat(restated.path("synthesis").path("status").asText()).isEqualTo("ok");
        assertThat(restated.path("synthesis").path("reason").isNull()).isTrue();
        JsonNode synthesized = restated.path("synthesized");
        assertThat(synthesized.path("text").asText())
                .isEqualTo("Reproduce the recovery path [E1]; storage was replaced [E1].");
        assertThat(synthesized.path("model").asText()).isEqualTo("served-model");
        assertThat(synthesized.path("promptVersion").asText()).isEqualTo("continuation-synthesis-v1");
        assertThat(synthesized.path("citations")).hasSize(1);
        assertThat(synthesized.path("citations").path(0).path("handle").asText())
                .isEqualTo("E1");
        assertThat(synthesized.path("citations").path(0).path("eventId").asText())
                .isEqualTo(eventId);
        assertThat(synthesized.path("citations").path(0).path("href").asText())
                .isEqualTo(candidate.path("evidence").path(0).path("href").asText());
        assertThat(restated.path("candidate").path("prompt").asText())
                .isEqualTo(candidate.path("prompt").asText());

        assertThat(model.requests).hasSize(1);
        String request = model.requests.getFirst();
        JsonNode body = mapper.readTree(request.substring(request.indexOf('\n') + 1));
        assertThat(request).startsWith("POST /v1/chat/completions");
        assertThat(body.path("max_tokens").asInt()).isEqualTo(512);
        assertThat(body.path("messages").path(0).path("content").asText())
                .isEqualTo(ContinuationSynthesisPrompt.SYSTEM);
        assertThat(body.path("messages").path(1).path("content").asText())
                .contains("[E1]")
                .contains("```text\nReproduce the recovery path\n```")
                .doesNotContain(eventId)
                .doesNotContain(
                        candidate.path("evidence").path(0).path("sessionId").asText());
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "200|Restated [E1].|length|0|truncated",
                "200|Restated [E9].|stop|0|invalid_reference",
                "200|Restated [E1]; see 11111111-2222-4333-8444-555555555555.|stop|0|invalid_reference",
                "200|Nothing cited.|stop|0|invalid_reference",
                "200|'  '|stop|0|empty",
                "500|unused|stop|0|unavailable",
                "200|Restated [E1].|stop|2500|timeout"
            })
    void anyRejectedOrFailedReplyDegradesWithItsReasonAfterOneCall(
            int status, String content, String finishReason, long delay, String reason) throws Exception {
        String project = ContinuationSynthesisFixture.project(http, mapper, "Reproduce the recovery path");
        JsonNode candidate = ContinuationSynthesisFixture.continuations(http, mapper, project)
                .path("candidates")
                .path(0);
        model.reply.set(new ContinuationSynthesisFixture.Reply(
                status,
                status == 200
                        ? ContinuationSynthesisFixture.completion(content, finishReason)
                        : "{\"error\":\"PRIVATE upstream detail\"}",
                delay));

        JsonNode restated = ContinuationSynthesisFixture.synthesize(
                http,
                mapper,
                Map.of("project", project, "candidateId", candidate.path("id").asText()),
                status().isOk());

        assertThat(restated.path("synthesized").isNull()).isTrue();
        assertThat(restated.path("synthesis").path("status").asText()).isEqualTo("degraded");
        assertThat(restated.path("synthesis").path("reason").asText()).isEqualTo(reason);
        assertThat(restated.toString()).doesNotContain("PRIVATE upstream detail");
        assertThat(restated.path("candidate").path("prompt").asText())
                .isEqualTo(candidate.path("prompt").asText());
        assertThat(model.requests).hasSize(1);
    }
}
