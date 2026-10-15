package dev.nathan.sbaagentic.memory.internal.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/** Enabled synthesis with a non-loopback model URL is refused before any connection is attempted. */
@SpringBootTest(
        properties = {
            "spring.datasource.url=jdbc:sqlite:${java.io.tmpdir}/bb-continuation-synthesis-remote-${random.uuid}.db",
            "sba.continuation.synthesis.enabled=true",
            "sba.local-ai.enabled=true",
            "sba.local-ai.base-url=http://192.0.2.10:1234",
            "sba.local-ai.timeout=1s",
            "sba.summary.backend=local",
            "sba.elasticsearch.enabled=false",
            "sba.memory.embedding.enabled=false",
            "sba.ask.embedding-enabled=false",
            "sba.judge.enabled=false"
        })
@AutoConfigureMockMvc
class ContinuationSynthesisRemoteRefusedTest {

    @Autowired
    MockMvc http;

    @Autowired
    ObjectMapper mapper;

    @Test
    void aNonLoopbackModelIsReportedUnavailableAndRefused() throws Exception {
        String project = ContinuationSynthesisFixture.project(http, mapper, "Reproduce the recovery path");
        JsonNode built = ContinuationSynthesisFixture.continuations(http, mapper, project);
        assertThat(built.path("synthesisAvailable").asBoolean()).isFalse();

        long started = System.nanoTime();
        JsonNode restated = ContinuationSynthesisFixture.synthesize(
                http,
                mapper,
                Map.of(
                        "project",
                        project,
                        "candidateId",
                        built.path("candidates").path(0).path("id").asText()),
                status().isOk());

        assertThat(restated.path("synthesized").isNull()).isTrue();
        assertThat(restated.path("synthesis").path("reason").asText()).isEqualTo("remote_model_not_allowed");
        // A connection attempt to the unroutable documentation address would take the full timeout.
        assertThat(System.nanoTime() - started).isLessThan(900_000_000L);
    }
}
