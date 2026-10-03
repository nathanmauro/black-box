package dev.nathan.sbaagentic.recording;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/** Exercises accepted capture bytes through HTTP and the real, disposable canonical SQLite store. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.datasource.url=jdbc:sqlite:${java.io.tmpdir}/bb-text-boundary-${random.uuid}.db",
            "sba.local-ai.enabled=false",
            "sba.elasticsearch.enabled=false",
            "sba.memory.embedding.enabled=false",
            "sba.summary.backend=local",
            "sba.judge.enabled=false"
        })
class CaptureTextBoundaryHttpTest {
    @LocalServerPort
    int port;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    JdbcTemplate jdbc;

    private final HttpClient client = HttpClient.newHttpClient();

    @ParameterizedTest
    @ValueSource(ints = {19_998, 19_999, 20_000})
    void captureLimitKeepsWholeCharactersInStoredAndHttpText(int prefixLength) throws Exception {
        String prefix = "x".repeat(prefixLength);
        String input = prefix + "🧪" + "tail";
        String expected = (prefixLength == 19_998 ? prefix + "🧪" : prefix) + "\n[truncated]";
        JsonNode event = capture(input, Map.of());

        assertThat(event.path("text").asText()).isEqualTo(expected);
        assertThat(jdbc.queryForObject(
                        "SELECT text FROM agent_events WHERE id = ?",
                        String.class,
                        event.path("id").asText()))
                .isEqualTo(expected);
    }

    @Test
    void captureExactlyAtLimitRemainsComplete() throws Exception {
        String input = "x".repeat(19_998) + "🧪";

        assertThat(capture(input, Map.of()).path("text").asText()).isEqualTo(input);
    }

    @ParameterizedTest
    @ValueSource(ints = {49_998, 49_999, 50_000})
    void nestedScalarsKeepWholeCharactersAtRedactionScanLimit(int prefixLength) throws Exception {
        String prefix = "x".repeat(prefixLength);
        String input = prefix + "🧪" + "tail";
        String expected = (prefixLength == 49_998 ? prefix + "🧪" : prefix) + " …[truncated]";
        JsonNode event = capture("Nested scalar boundary", Map.of("nested", Map.of("value", input)));

        assertThat(event.path("metadata").path("nested").path("value").asText()).isEqualTo(expected);
        assertThat(mapper.readTree(event.path("toolInputJson").asText())
                        .path("nested")
                        .path("value")
                        .asText())
                .isEqualTo(expected);
        assertThat(mapper.readTree(event.path("toolOutputJson").asText())
                        .path("nested")
                        .path("value")
                        .asText())
                .isEqualTo(expected);
        String stored = jdbc.queryForObject(
                "SELECT metadata_json FROM agent_events WHERE id = ?",
                String.class,
                event.path("id").asText());
        assertThat(mapper.readTree(stored).path("nested").path("value").asText())
                .isEqualTo(expected);
    }

    private JsonNode capture(String text, Map<String, Object> nested) throws Exception {
        String body = mapper.writeValueAsString(Map.of(
                "source",
                "manual",
                "clientSessionId",
                UUID.randomUUID().toString(),
                "eventType",
                "Observation",
                "text",
                text,
                "cwd",
                "/fixture/text-boundary",
                "metadata",
                nested,
                "toolInput",
                nested,
                "toolOutput",
                nested));
        HttpResponse<String> captured = client.send(
                HttpRequest.newBuilder(endpoint("/api/events"))
                        .timeout(Duration.ofSeconds(15))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(captured.statusCode()).as(captured.body()).isEqualTo(200);
        String id = mapper.readTree(captured.body()).path("eventId").asText();
        assertThat(id).isNotBlank();
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(endpoint("/api/events/" + id))
                        .timeout(Duration.ofSeconds(15))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);

        return mapper.readTree(response.body());
    }

    private URI endpoint(String path) {

        return URI.create("http://127.0.0.1:" + port + path);
    }
}
