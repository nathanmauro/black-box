package dev.nathan.sbaagentic.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.nathan.sbaagentic.SbaAgenticApplication;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

/** Actual HTTP and SQL contracts against disposable SQLite, without model or external services. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProjectMeldMetadataHttpTest {
    @TempDir
    static Path tempDir;

    private final HttpClient client = HttpClient.newHttpClient();
    private ServletWebServerApplicationContext app;
    private ObjectMapper mapper;
    private JdbcTemplate jdbc;
    private String base;

    @BeforeAll
    void start() {
        app = (ServletWebServerApplicationContext) new SpringApplicationBuilder(SbaAgenticApplication.class)
                .run(
                        "--spring.config.location=classpath:/application.yml",
                        "--spring.datasource.url=jdbc:sqlite:" + tempDir.resolve("melds.db"),
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
    }

    @AfterAll
    void stop() {
        if (app != null) app.close();
        client.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"top-level-null", "nested-null", "mixed", "omitted", "whole-null"})
    void metadataRoundTripsWithoutChangingCaptures(String variant) throws Exception {
        String project = "/fixture/meld-metadata-" + UUID.randomUUID();
        JsonNode first = seed(project);
        JsonNode second = seed(project);
        List<String> sessions = List.of(
                second.path("sessionId").asText(), first.path("sessionId").asText());
        ObjectNode request = request(project, sessions);
        JsonNode expected =
                switch (variant) {
                    case "top-level-null" -> mapper.readTree("{\"sourceHash\":null}");
                    case "nested-null" -> mapper.readTree("{\"source\":{\"hash\":null}}");
                    case "mixed" -> mapper.readTree("""
                    {"sourceHash":null,"nested":{"optional":null},"items":[null,1,"two",{"absent":null}],
                     "flag":false,"empty":"","number":1.25}
                    """);
                    default -> mapper.createObjectNode();
                };
        if (variant.equals("whole-null")) request.putNull("metadata");
        else if (!variant.equals("omitted")) request.set("metadata", expected);
        List<Map<String, Object>> events = jdbc.queryForList("SELECT * FROM agent_events ORDER BY id");
        List<Map<String, Object>> originalSessions = jdbc.queryForList("SELECT * FROM agent_sessions ORDER BY id");
        Map<String, Integer> counts = counts();

        JsonNode saved = http("POST", "/api/melds", request, 200);
        String id = saved.path("id").asText();
        assertThat(id).isNotBlank();
        assertThat(saved.path("metadata")).isEqualTo(expected);
        assertThat(saved.path("canonicalKey").asText()).isEqualTo(project);
        assertThat(saved.path("body").asText()).isEqualTo(request.path("body").asText());
        assertThat(saved.path("sessions").findValuesAsText("id")).containsExactlyElementsOf(sessions);
        JsonNode listing = http("GET", "/api/projects/" + ProjectKey.of(project).encoded() + "/melds", null, 200);
        assertThat(listing).hasSize(1);
        assertThat(listing.get(0)).isEqualTo(saved);
        assertThat(jdbc.queryForObject("SELECT metadata_json FROM session_melds WHERE id=?", String.class, id))
                .isEqualTo(expected.isEmpty() ? null : mapper.writeValueAsString(expected));
        assertThat(jdbc.queryForList(
                        "SELECT session_id FROM session_meld_inputs WHERE meld_id=? ORDER BY input_order",
                        String.class,
                        id))
                .containsExactlyElementsOf(sessions);
        assertThat(counts()).isEqualTo(Map.of("melds", counts.get("melds") + 1, "inputs", counts.get("inputs") + 2));
        assertThat(jdbc.queryForList("SELECT * FROM agent_events ORDER BY id")).isEqualTo(events);
        assertThat(jdbc.queryForList("SELECT * FROM agent_sessions ORDER BY id"))
                .isEqualTo(originalSessions);
    }

    @ParameterizedTest
    @ValueSource(strings = {"blank-body", "foreign-session", "missing-session", "empty-selection"})
    void invalidSaveLeavesMeldsInputsAndCapturesUnchanged(String variant) throws Exception {
        String project = "/fixture/meld-invalid-" + UUID.randomUUID();
        JsonNode own = seed(project);
        ObjectNode request = request(project, List.of(own.path("sessionId").asText()));
        request.set("metadata", mapper.readTree("{\"sourceHash\":null}"));
        switch (variant) {
            case "blank-body" -> request.put("body", " \n ");
            case "foreign-session" ->
                request.set(
                        "sessionIds",
                        mapper.valueToTree(List.of(
                                seed(project + "-foreign").path("sessionId").asText())));
            case "missing-session" ->
                request.set(
                        "sessionIds",
                        mapper.valueToTree(List.of(UUID.randomUUID().toString())));
            default -> request.putArray("sessionIds");
        }
        Map<String, Integer> counts = counts();
        List<Map<String, Object>> events = jdbc.queryForList("SELECT * FROM agent_events ORDER BY id");
        List<Map<String, Object>> sessions = jdbc.queryForList("SELECT * FROM agent_sessions ORDER BY id");
        JsonNode error = http("POST", "/api/melds", request, 400);
        assertThat(error.path("error").path("message").asText()).isNotBlank();
        assertThat(error.toString()).doesNotContain("NullPointerException", "java.lang.");
        assertThat(counts()).isEqualTo(counts);
        assertThat(jdbc.queryForList("SELECT * FROM agent_events ORDER BY id")).isEqualTo(events);
        assertThat(jdbc.queryForList("SELECT * FROM agent_sessions ORDER BY id"))
                .isEqualTo(sessions);
    }

    @Test
    void applicationResultOwnsAnUnmodifiableDefensiveOuterMap() throws Exception {
        String project = "/fixture/meld-copy-" + UUID.randomUUID();
        JsonNode event = seed(project);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("sourceHash", null);
        metadata.put("note", "original");
        ProjectSavedMeld saved = app.getBean(ProjectMeldOperations.class)
                .save(new ProjectMeldSaveRequest(
                        ProjectKey.of(project).encoded(),
                        "Defensive metadata",
                        "Saved body",
                        null,
                        null,
                        null,
                        "export_bundle",
                        true,
                        List.of(event.path("sessionId").asText()),
                        metadata));
        metadata.put("note", "changed by caller");
        metadata.put("later", true);
        assertThat(saved.metadata())
                .containsEntry("sourceHash", null)
                .containsEntry("note", "original")
                .doesNotContainKey("later");
        assertThat(saved.metadata().keySet()).containsExactly("sourceHash", "note");
        assertThatThrownBy(() -> saved.metadata().put("later", true)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> saved.metadata().remove("sourceHash"))
                .isInstanceOf(UnsupportedOperationException.class);
        JsonNode listing = http("GET", "/api/projects/" + ProjectKey.of(project).encoded() + "/melds", null, 200);
        assertThat(listing.get(0).path("metadata")).isEqualTo(mapper.valueToTree(saved.metadata()));
    }

    private JsonNode seed(String project) throws Exception {

        return http(
                "POST",
                "/api/events",
                Map.of(
                        "source",
                        "manual",
                        "clientSessionId",
                        UUID.randomUUID().toString(),
                        "cwd",
                        project,
                        "eventType",
                        "Observation",
                        "text",
                        "Original project evidence"),
                200);
    }

    private ObjectNode request(String project, List<String> sessions) {
        ObjectNode request = mapper.createObjectNode();
        request.put("projectKey", ProjectKey.of(project).encoded());
        request.put("title", "Saved synthesis");
        request.put("body", "Saved locally without a provider");
        request.put("executionMode", "export_bundle");
        request.set("sessionIds", mapper.valueToTree(sessions));

        return request;
    }

    private Map<String, Integer> counts() {

        return Map.of(
                "melds",
                jdbc.queryForObject("SELECT count(*) FROM session_melds", Integer.class),
                "inputs",
                jdbc.queryForObject("SELECT count(*) FROM session_meld_inputs", Integer.class));
    }

    private JsonNode http(String method, String path, Object body, int expected) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .method(
                        method,
                        body == null
                                ? HttpRequest.BodyPublishers.noBody()
                                : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(expected);

        return mapper.readTree(response.body());
    }
}
