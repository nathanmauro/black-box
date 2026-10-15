package dev.nathan.sbaagentic.memory.internal.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nathan.sbaagentic.memory.ContinuationSynthesizer;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Shipped defaults: the local model is enabled and reachable on loopback, yet with synthesis off no
 * synthesizer exists and neither continuation endpoint makes a single model request.
 */
@SpringBootTest(
        properties = {
            "spring.datasource.url=jdbc:sqlite:${java.io.tmpdir}/bb-continuation-synthesis-off-${random.uuid}.db",
            "sba.local-ai.enabled=true",
            "sba.summary.backend=local",
            "sba.elasticsearch.enabled=false",
            "sba.memory.embedding.enabled=false",
            "sba.ask.embedding-enabled=false",
            "sba.judge.enabled=false"
        })
@AutoConfigureMockMvc
class ContinuationSynthesisDisabledTest {

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

    @Autowired
    ObjectProvider<ContinuationSynthesizer> synthesizer;

    @Test
    void theDefaultConfigurationHasNoSynthesizerAndMakesNoModelRequest() throws Exception {
        assertThat(synthesizer.getIfAvailable()).isNull();
        String project = ContinuationSynthesisFixture.project(http, mapper, "Reproduce the recovery path");
        JsonNode built = ContinuationSynthesisFixture.continuations(http, mapper, project);
        JsonNode candidate = built.path("candidates").path(0);
        assertThat(built.path("synthesisAvailable").asBoolean()).isFalse();

        JsonNode restated = ContinuationSynthesisFixture.synthesize(
                http,
                mapper,
                Map.of("project", project, "candidateId", candidate.path("id").asText()),
                status().isOk());

        assertThat(restated.has("synthesized")).isTrue();
        assertThat(restated.path("synthesized").isNull()).isTrue();
        assertThat(restated.path("synthesis").path("status").asText()).isEqualTo("degraded");
        assertThat(restated.path("synthesis").path("reason").asText()).isEqualTo("disabled");
        assertThat(restated.path("candidate").path("prompt").asText())
                .isEqualTo(candidate.path("prompt").asText());

        // The same typed envelope as other unknown-record 404s, such as an unknown idea key.
        JsonNode missing = ContinuationSynthesisFixture.synthesize(
                        http,
                        mapper,
                        Map.of("project", project, "candidateId", "bbc1-000000000000000000000000"),
                        status().isNotFound())
                .path("error");
        assertThat(missing.path("status").asInt()).isEqualTo(404);
        assertThat(missing.path("type").asText()).isEqualTo("request_failed");
        assertThat(missing.path("message").asText())
                .isEqualTo("Candidate not found in the current record; build prompts again.");
        assertThat(ContinuationSynthesisFixture.synthesize(
                                http, mapper, Map.of("project", project, "candidateId", " "), status().isBadRequest())
                        .path("error")
                        .path("type")
                        .asText())
                .isEqualTo("invalid_argument");
        assertThat(ContinuationSynthesisFixture.synthesize(
                                http,
                                mapper,
                                Map.of(
                                        "project",
                                        "/",
                                        "candidateId",
                                        candidate.path("id").asText()),
                                status().isBadRequest())
                        .path("error")
                        .path("type")
                        .asText())
                .isEqualTo("invalid_argument");
        assertThat(model.requests).isEmpty();
    }
}
