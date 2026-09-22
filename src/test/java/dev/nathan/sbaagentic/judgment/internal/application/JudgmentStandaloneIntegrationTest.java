package dev.nathan.sbaagentic.judgment.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import dev.nathan.sbaagentic.judgment.internal.adapter.out.http.JevTransport;
import dev.nathan.sbaagentic.recording.EventIngestRequest;
import dev.nathan.sbaagentic.recording.IngestResponse;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/** Real HTTP capture and recall without any stream consumer or working external judge. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.datasource.url=jdbc:sqlite:${java.io.tmpdir}/bb-standalone-judge-${random.uuid}.db",
            "sba.judge.enabled=true",
            "sba.judge.provider=jev",
            "SBA_JUDGE_API_KEY=fixture-only",
            "sba.local-ai.enabled=false",
            "sba.summary.backend=local",
            "sba.elasticsearch.enabled=false",
            "sba.memory.embedding.enabled=false",
            "server.shutdown=immediate"
        })
@Import(JudgmentStandaloneIntegrationTest.FakeProvider.class)
class JudgmentStandaloneIntegrationTest {
    @Autowired
    TestRestTemplate http;

    @Autowired
    FailingTransport transport;

    @Test
    void captureAndRecallWorkWhileJudgeIsBlockedAndAfterItFailsWithoutAnyConsumer() throws Exception {
        String text = "Standalone cortex fixture: preserve the canonical capture and recall path.";
        try {
            var response = http.postForEntity("/api/events", request("one", text), IngestResponse.class);
            assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
            assertThat(transport.started.await(5, TimeUnit.SECONDS)).isTrue();
            String id = response.getBody().eventId();
            assertThat(http.getForObject("/api/events/" + id, JsonNode.class)
                            .path("text")
                            .asText())
                    .isEqualTo(text);
            assertThat(http.getForObject("/api/recall?scope=/fixtures/standalone-cortex", JsonNode.class)
                            .path("items")
                            .toString())
                    .contains(id);
        } finally {
            transport.release.countDown();
        }
        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(http.getForObject("/api/health/judge", JsonNode.class)
                                .path("failures")
                                .asLong())
                        .isPositive());
        var afterFailure = http.postForEntity("/api/events", request("two", text), IngestResponse.class);
        assertThat(afterFailure.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(http.getForObject("/api/recall?scope=/fixtures/standalone-cortex", JsonNode.class)
                        .path("items")
                        .toString())
                .contains(afterFailure.getBody().eventId());
    }

    private static EventIngestRequest request(String turn, String text) {

        return new EventIngestRequest(
                "codex",
                "standalone-cortex",
                turn,
                "Decision",
                "assistant",
                text,
                "/fixtures/standalone-cortex",
                null,
                null,
                null,
                Map.of(),
                Instant.now());
    }

    static class FailingTransport implements JevTransport {
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        @Override
        public String post(String endpoint, String apiKey, String body, Duration timeout)
                throws IOException, InterruptedException {
            started.countDown();
            release.await(10, TimeUnit.SECONDS);
            throw new IOException("Fixture provider unavailable; no network request was made");
        }
    }

    @TestConfiguration
    static class FakeProvider {
        @Bean
        @Primary
        FailingTransport failingTransport() {

            return new FailingTransport();
        }
    }
}
