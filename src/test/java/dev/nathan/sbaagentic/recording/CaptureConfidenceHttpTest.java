package dev.nathan.sbaagentic.recording;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nathan.sbaagentic.SbaAgenticApplication;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

/** Real REST/MCP validation against disposable SQLite, with all external systems disabled. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CaptureConfidenceHttpTest {
    @TempDir
    static Path tempDir;

    private final HttpClient client = HttpClient.newHttpClient();
    private ServletWebServerApplicationContext app;
    private ObjectMapper mapper;
    private JdbcTemplate jdbc;
    private String base;
    private String mcpSession;
    private int requestId;

    @BeforeAll
    void start() throws Exception {
        app = (ServletWebServerApplicationContext) new SpringApplicationBuilder(SbaAgenticApplication.class)
                .run(
                        "--spring.config.location=classpath:/application.yml",
                        "--spring.datasource.url=jdbc:sqlite:" + tempDir.resolve("confidence.db"),
                        "--server.address=127.0.0.1",
                        "--server.port=0",
                        "--sba.auth.enabled=false",
                        "--sba.editor.enabled=false",
                        "--sba.local-ai.enabled=false",
                        "--sba.summary.backend=local",
                        "--sba.elasticsearch.enabled=false",
                        "--sba.memory.embedding.enabled=false",
                        "--sba.ask.embedding-enabled=false",
                        "--sba.judge.enabled=false",
                        "--spring.main.banner-mode=off",
                        "--logging.level.root=WARN");
        mapper = app.getBean(ObjectMapper.class);
        jdbc = app.getBean(JdbcTemplate.class);
        base = "http://127.0.0.1:" + app.getWebServer().getPort();
        mcp(mapper.writeValueAsString(Map.of(
                "jsonrpc",
                "2.0",
                "id",
                ++requestId,
                "method",
                "initialize",
                "params",
                Map.of(
                        "protocolVersion",
                        "2024-11-05",
                        "capabilities",
                        Map.of(),
                        "clientInfo",
                        Map.of("name", "confidence-fixture", "version", "1")))));
    }

    @AfterAll
    void stop() {
        if (app != null) app.close();
        client.close();
    }

    static Stream<Arguments> invalidConfidences() {

        return Stream.of("http", "mcp")
                .flatMap(transport -> Stream.of("Decision", "Projection")
                        .flatMap(kind -> Stream.of("-0.1", "1.1", "\"NaN\"", "\"Infinity\"", "\"-Infinity\"", "1e309")
                                .map(value -> Arguments.of(transport, kind, value))));
    }

    @ParameterizedTest(name = "{0} {1} confidence {2}")
    @MethodSource("invalidConfidences")
    void rejectsInvalidConfidenceBeforeCreatingAnyRows(String transport, String kind, String value) throws Exception {
        long events = count("agent_events");
        long sessions = count("agent_sessions");
        var response = capture(transport, kind, arguments(kind, value));
        assertError(response, transport, kind.equals("Decision") ? "confidence" : "paths[2].confidence");
        assertThat(count("agent_events")).isEqualTo(events);
        assertThat(count("agent_sessions")).isEqualTo(sessions);
    }

    static Stream<Arguments> optionalConfidences() {

        return Stream.of("http", "mcp")
                .flatMap(transport -> Stream.of("Decision", "Projection")
                        .flatMap(kind -> Stream.of("omitted", "null", "0", "1")
                                .map(value -> Arguments.of(transport, kind, value))));
    }

    @ParameterizedTest(name = "{0} {1} confidence {2}")
    @MethodSource("optionalConfidences")
    void preservesOptionalConfidenceAndInclusiveBoundaries(String transport, String kind, String value)
            throws Exception {
        JsonNode captured = success(capture(transport, kind, arguments(kind, value)), transport);
        String eventId = captured.path("eventId").asText();
        JsonNode metadata = mapper.readTree(
                jdbc.queryForObject("SELECT metadata_json FROM agent_events WHERE id=?", String.class, eventId));
        JsonNode fields =
                kind.equals("Decision") ? metadata : metadata.path("paths").get(0);
        if (value.equals("null") || value.equals("omitted"))
            assertThat(fields.has("confidence")).isFalse();
        else assertThat(fields.path("confidence").asDouble()).isEqualTo(Double.parseDouble(value));
        if (kind.equals("Projection")) assertThat(metadata.path("paths")).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"http", "mcp"})
    void filteredPathsDoNotAcquireNewValidationRequirements(String transport) throws Exception {
        String raw = baseArguments() + ",\"paths\":[null,{\"title\":\" \",\"confidence\":2},{\"title\":\"Retained\"}]}";
        JsonNode captured = success(capture(transport, "Projection", raw), transport);
        JsonNode metadata = mapper.readTree(jdbc.queryForObject(
                "SELECT metadata_json FROM agent_events WHERE id=?",
                String.class,
                captured.path("eventId").asText()));
        assertThat(metadata.path("paths")).hasSize(1);
        assertThat(metadata.path("paths").get(0).path("title").asText()).isEqualTo("Retained");
    }

    @Test
    void mcpRetainsFirstFivePathsWithoutValidatingDiscardedSixthPath() throws Exception {
        String raw = baseArguments() + ",\"paths\":[{\"title\":\"1\"},{\"title\":\"2\"},{\"title\":\"3\"},"
                + "{\"title\":\"4\"},{\"title\":\"5\"},{\"title\":\"discarded\",\"confidence\":2}]}";
        JsonNode captured = success(capture("mcp", "Projection", raw), "mcp");
        JsonNode metadata = mapper.readTree(jdbc.queryForObject(
                "SELECT metadata_json FROM agent_events WHERE id=?",
                String.class,
                captured.path("eventId").asText()));
        assertThat(metadata.path("paths")).hasSize(5);
        assertThat(metadata.toString()).doesNotContain("discarded");
        long before = count("agent_events");
        assertThat(capture("http", "Projection", raw).status()).isEqualTo(400); // Existing REST size contract.
        assertThat(count("agent_events")).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"http", "mcp"})
    void invalidReplacementConfidenceDoesNotChangeOriginalOrRelations(String transport) throws Exception {
        JsonNode original = success(capture(transport, "Decision", arguments("Decision", "1")), transport);
        String eventId = original.path("eventId").asText();
        Map<String, Object> event = jdbc.queryForMap("SELECT * FROM agent_events WHERE id=?", eventId);
        long events = count("agent_events"),
                sessions = count("agent_sessions"),
                relations = count("decision_replacements");
        String raw = arguments("Decision", "1.1");
        raw = raw.substring(0, raw.length() - 1) + ",\"rationale\":\"Reviewed replacement\",\"supersedes\":\"" + eventId
                + "\"}";
        assertError(capture(transport, "Decision", raw), transport, "confidence");
        assertThat(count("agent_events")).isEqualTo(events);
        assertThat(count("agent_sessions")).isEqualTo(sessions);
        assertThat(count("decision_replacements")).isEqualTo(relations);
        assertThat(jdbc.queryForMap("SELECT * FROM agent_events WHERE id=?", eventId))
                .isEqualTo(event);
    }

    private String baseArguments() {

        return "{\"source\":\"manual\",\"clientSessionId\":\"confidence-" + UUID.randomUUID()
                + "\",\"repo\":\"/fixture/confidence\"";
    }

    private String arguments(String kind, String confidence) {
        String field = confidence.equals("omitted") ? "" : ",\"confidence\":" + confidence;

        return kind.equals("Decision")
                ? baseArguments() + ",\"decision\":\"Fixture decision\"" + field + "}"
                : baseArguments() + ",\"paths\":[null,{\"title\":\" \"},{\"title\":\"Future\"" + field + "}]}";
    }

    private Response capture(String transport, String kind, String arguments) throws Exception {
        if (transport.equals("mcp"))

            return mcp("{\"jsonrpc\":\"2.0\",\"id\":" + ++requestId
                    + ",\"method\":\"tools/call\",\"params\":{\"name\":\"capture" + kind + "\",\"arguments\":"
                    + arguments + "}}");

        return post("/api/" + (kind.equals("Decision") ? "decisions" : "projections"), arguments, false);
    }

    private Response mcp(String body) throws Exception {
        Response response = post("/mcp", body, true);
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body().has("error")).as(response.body().toString()).isFalse();

        return new Response(response.status(), response.body().path("result"));
    }

    private Response post(String path, String body, boolean mcp) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream");
        if (mcp && mcpSession != null) request.header("Mcp-Session-Id", mcpSession);
        var response = client.send(
                request.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        if (mcp) response.headers().firstValue("Mcp-Session-Id").ifPresent(value -> mcpSession = value);
        String content = response.body();
        if (response.headers().firstValue("Content-Type").orElse("").contains("text/event-stream"))
            content = content.lines()
                    .filter(line -> line.startsWith("data:"))
                    .map(line -> line.substring(5).strip())
                    .findFirst()
                    .orElseThrow();

        return new Response(response.statusCode(), mapper.readTree(content));
    }

    private void assertError(Response response, String transport, String field) {
        String message;
        if (transport.equals("mcp")) {
            assertThat(response.body().path("isError").asBoolean())
                    .as(response.body().toString())
                    .isTrue();
            message = response.body().path("content").get(0).path("text").asText();
        } else {
            assertThat(response.status()).isEqualTo(400);
            assertThat(response.body().path("error").path("type").asText()).isEqualTo("invalid_argument");
            message = response.body().path("error").path("message").asText();
        }
        assertThat(message).isEqualTo(field + " must be a finite number between 0.0 and 1.0.");
        assertThat(response.body().toString()).doesNotContain("java.lang.", "StructuredCaptureService");
    }

    private JsonNode success(Response response, String transport) throws Exception {
        assertThat(response.status()).isEqualTo(200);
        if (!transport.equals("mcp"))

            return response.body();

        assertThat(response.body().path("isError").asBoolean())
                .as(response.body().toString())
                .isFalse();

        return mapper.readTree(
                response.body().path("content").get(0).path("text").asText());
    }

    private long count(String table) {

        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    private record Response(int status, JsonNode body) {}
}
