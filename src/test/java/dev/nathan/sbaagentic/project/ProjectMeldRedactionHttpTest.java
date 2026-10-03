package dev.nathan.sbaagentic.project;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.util.ArrayList;
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

/** Synthetic credentials through real save, canonical storage and read paths on private fixtures. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProjectMeldRedactionHttpTest {
    private static final String CREDENTIAL = "password='FAKE MELD CREDENTIAL'";
    private static final String SAFE = "password='[REDACTED]'";

    @TempDir
    static Path tempDir;

    private Fixture standard;

    protected List<String> databaseArguments(Path database) throws Exception {

        return List.of("--spring.datasource.url=jdbc:sqlite:" + database);
    }

    protected void cleanupDatabases() throws Exception {}

    @BeforeAll
    void start() throws Exception {
        standard = fixture("--sba.ingestion.redact-enabled=true");
    }

    @AfterAll
    void stop() throws Exception {
        try {
            if (standard != null) standard.close();
        } finally {
            cleanupDatabases();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"meld", "assigned-braid", "unassigned-braid"})
    void defaultRedactionMatchesSaveStorageAndReadWithoutChangingCaptures(String variant) throws Exception {
        Fixture f = standard;
        ObjectNode request = f.request(variant.equals("unassigned-braid"));
        for (String field : List.of("title", "body", "provider", "model", "promptVersion"))
            request.put(field, CREDENTIAL);
        request.set("metadata", f.mapper.readTree("""
                {"kind":"braid","optional":null,"nested":{"api_key":"FAKE NESTED CREDENTIAL","count":2},
                 "items":[null,false,1.25,"password='FAKE MELD CREDENTIAL'"],"tokenCount":1234}
                """));
        if (variant.equals("meld")) ((ObjectNode) request.path("metadata")).put("kind", "meld");
        List<Map<String, Object>> events = f.jdbc.queryForList("SELECT * FROM agent_events ORDER BY id");
        List<Map<String, Object>> sessions = f.jdbc.queryForList("SELECT * FROM agent_sessions ORDER BY id");
        JsonNode saved = f.saveAndCheck(request);
        assertThat(f.jdbc.queryForList("SELECT * FROM agent_events ORDER BY id"))
                .isEqualTo(events);
        assertThat(f.jdbc.queryForList("SELECT * FROM agent_sessions ORDER BY id"))
                .isEqualTo(sessions);
        assertThat(saved.toString()).doesNotContain("FAKE");
        for (String field : List.of("title", "body", "provider", "model", "promptVersion"))
            assertThat(saved.path(field).asText()).isEqualTo(SAFE);
        ObjectNode expected = (ObjectNode) f.mapper.readTree("""
                {"kind":"braid","optional":null,"nested":{"api_key":"[REDACTED]","count":2},
                 "items":[null,false,1.25,"password='[REDACTED]'"],"tokenCount":"[REDACTED]"}
                """);
        expected.put("kind", variant.equals("meld") ? "meld" : "braid");
        assertThat(saved.path("metadata")).isEqualTo(expected);
        assertThat(request.path("metadata").path("tokenCount").asInt()).isEqualTo(1234);
        assertThat(saved.path("metadata").path("tokenCount").isTextual()).isTrue();
        assertThat(saved.path("metadata").path("tokenCount").asText()).isEqualTo("[REDACTED]");
        assertThat(new ArrayList<>(saved.path("metadata").properties()).stream().map(Map.Entry::getKey))
                .containsExactly("kind", "optional", "nested", "items", "tokenCount");
    }

    @Test
    void disabledRedactionRetainsLargeScalarsAndSecretNamedMetadata() throws Exception {
        try (Fixture f = fixture("--sba.ingestion.redact-enabled=false")) {
            for (boolean unassigned : List.of(false, true)) {
                ObjectNode request = f.request(unassigned);
                String large = "a".repeat(49_999) + "😀" + CREDENTIAL;
                for (String field : List.of("title", "body", "provider", "model", "promptVersion"))
                    request.put(field, large);
                ObjectNode metadata = (ObjectNode) request.path("metadata");
                metadata.putNull("api_key");
                metadata.put("tokenCount", 12);
                metadata.put("text", large);
                metadata.putArray("nested").addNull().addObject().put("password", "FAKE DISABLED CREDENTIAL");
                JsonNode saved = f.saveAndCheck(request);
                for (String field : List.of("title", "body", "provider", "model", "promptVersion", "metadata"))
                    assertThat(saved.path(field)).isEqualTo(request.path(field));
            }
        }
    }

    @Test
    void customPatternsKeepClassificationModesAndProvenanceIndependent() throws Exception {
        try (Fixture f = fixture(
                "--sba.ingestion.redact-enabled=true",
                "--sba.ingestion.redact-patterns[0]=FAKE_CUSTOM|kind|braid|export_bundle|/fixture|manual")) {
            for (boolean unassigned : List.of(false, true)) {
                ObjectNode request = f.request(unassigned);
                request.put("body", CREDENTIAL + " FAKE_CUSTOM");
                ObjectNode metadata = (ObjectNode) request.path("metadata");
                metadata.put("api_key", "FAKE DEFAULT UNMATCHED");
                metadata.put("tokenCount", 4321);
                metadata.putNull("optional");
                metadata.put("FAKE_CUSTOM", "changed key");
                metadata.put("[REDACTED]", "ordinary key");
                JsonNode saved = f.saveAndCheck(request);
                assertThat(saved.path("body").asText()).isEqualTo(CREDENTIAL + " [REDACTED]");
                assertThat(saved.path("executionMode").asText()).isEqualTo("export_bundle");
                assertThat(saved.path("metadata").has("kind")).isFalse();
                assertThat(saved.path("metadata")
                                .path("[REDACTED] (redacted key 1)")
                                .asText())
                        .isEqualTo("[REDACTED]");
                assertThat(saved.path("metadata")
                                .path("[REDACTED] (redacted key 2)")
                                .asText())
                        .isEqualTo("changed key");
                assertThat(saved.path("metadata").path("[REDACTED]").asText()).isEqualTo("ordinary key");
                assertThat(saved.path("metadata").path("api_key").asText()).isEqualTo("FAKE DEFAULT UNMATCHED");
                assertThat(saved.path("metadata").path("tokenCount").isIntegralNumber())
                        .isTrue();
                assertThat(saved.path("metadata").path("tokenCount").asInt()).isEqualTo(4321);
                assertThat(saved.path("metadata").path("optional").isNull()).isTrue();
                if (unassigned) {
                    JsonNode listing = f.http("GET", "/api/melds?kind=braid&scope=unassigned", null, 200);
                    assertThat(listing.path("items").findValuesAsText("id"))
                            .contains(saved.path("id").asText());
                }
            }
            ObjectNode invalid = f.request(true);
            ((ObjectNode) invalid.path("metadata")).put("kind", "FAKE_CUSTOM");
            int count = f.jdbc.queryForObject("SELECT count(*) FROM session_melds", Integer.class);
            f.http("POST", "/api/melds", invalid, 400);
            assertThat(f.jdbc.queryForObject("SELECT count(*) FROM session_melds", Integer.class))
                    .isEqualTo(count);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void enabledRedactionClipsLargeTextAndMetadataAtUnicodeSafeScanBoundary(boolean custom) throws Exception {
        if (custom) {
            try (Fixture f =
                    fixture("--sba.ingestion.redact-enabled=true", "--sba.ingestion.redact-patterns[0]=FAKE_CUSTOM")) {
                assertLargeScalars(f);
            }
        } else {
            assertLargeScalars(standard);
        }
    }

    private void assertLargeScalars(Fixture f) throws Exception {
        ObjectNode request = f.request(true);
        String prefix = "a".repeat(49_999);
        String large = prefix + "😀" + CREDENTIAL;
        String expected = prefix + " …[truncated]";
        for (String field : List.of("title", "body", "provider", "model", "promptVersion")) request.put(field, large);
        ObjectNode metadata = (ObjectNode) request.path("metadata");
        metadata.put("text", large);
        metadata.putArray("nested").add(large);
        JsonNode saved = f.saveAndCheck(request);
        for (String field : List.of("title", "body", "provider", "model", "promptVersion"))
            assertThat(saved.path(field).asText()).isEqualTo(expected);
        assertThat(saved.path("metadata").path("text").asText()).isEqualTo(expected);
        assertThat(saved.path("metadata").path("nested").get(0).asText()).isEqualTo(expected);
        assertThat(saved.toString()).doesNotContain("FAKE", "😀");
    }

    @Test
    void newSavesDoNotRewriteHistoricalRowsOrInputMetadata() throws Exception {
        Fixture f = standard;
        JsonNode original = f.saveAndCheck(f.request(true));
        String id = original.path("id").asText();
        // Model an already-persisted row from before this policy; no reader-side rewriting is allowed.
        String oldMetadata = "{ \"api_key\": \"FAKE HISTORICAL CREDENTIAL\", \"optional\": null }";
        f.jdbc.update(
                "UPDATE session_melds SET title=?,body=?,provider=?,model=?,prompt_version=?,metadata_json=? WHERE id=?",
                CREDENTIAL,
                CREDENTIAL,
                CREDENTIAL,
                CREDENTIAL,
                CREDENTIAL,
                oldMetadata,
                id);
        f.jdbc.update("UPDATE session_meld_inputs SET metadata_json=? WHERE meld_id=? AND input_order=0", "null", id);
        List<Map<String, Object>> parents = f.jdbc.queryForList("SELECT * FROM session_melds WHERE id=?", id);
        List<Map<String, Object>> inputs =
                f.jdbc.queryForList("SELECT * FROM session_meld_inputs WHERE meld_id=? ORDER BY input_order", id);
        ObjectNode request = f.request(true);
        request.put("body", CREDENTIAL);
        f.saveAndCheck(request);
        assertThat(f.jdbc.queryForList("SELECT * FROM session_melds WHERE id=?", id))
                .isEqualTo(parents);
        assertThat(f.jdbc.queryForList("SELECT * FROM session_meld_inputs WHERE meld_id=? ORDER BY input_order", id))
                .isEqualTo(inputs);
        JsonNode fetched = f.http("GET", "/api/melds/" + id, null, 200);
        assertThat(fetched.path("body").asText()).isEqualTo(CREDENTIAL);
        assertThat(fetched.path("metadata")).isEqualTo(f.mapper.readTree(oldMetadata));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void storageFailureRollsBackSanitizedParentAndOrderedInputs(boolean unassigned) throws Exception {
        Fixture f = standard;
        ObjectNode request = f.request(unassigned);
        request.put("body", CREDENTIAL);
        List<Map<String, Object>> parents = f.jdbc.queryForList("SELECT * FROM session_melds ORDER BY id");
        List<Map<String, Object>> inputs =
                f.jdbc.queryForList("SELECT * FROM session_meld_inputs ORDER BY meld_id,input_order");
        List<Map<String, Object>> events = f.jdbc.queryForList("SELECT * FROM agent_events ORDER BY id");
        List<Map<String, Object>> sessions = f.jdbc.queryForList("SELECT * FROM agent_sessions ORDER BY id");
        installInputFailure(f.jdbc);
        try {
            f.http("POST", "/api/melds", request, 500);
        } finally {
            removeInputFailure(f.jdbc);
        }
        assertThat(f.jdbc.queryForList("SELECT * FROM session_melds ORDER BY id"))
                .isEqualTo(parents);
        assertThat(f.jdbc.queryForList("SELECT * FROM session_meld_inputs ORDER BY meld_id,input_order"))
                .isEqualTo(inputs);
        assertThat(f.jdbc.queryForList("SELECT * FROM agent_events ORDER BY id"))
                .isEqualTo(events);
        assertThat(f.jdbc.queryForList("SELECT * FROM agent_sessions ORDER BY id"))
                .isEqualTo(sessions);
    }

    protected void installInputFailure(JdbcTemplate jdbc) {
        jdbc.execute("CREATE TRIGGER redaction_input_failure BEFORE INSERT ON session_meld_inputs "
                + "WHEN NEW.input_order=1 BEGIN SELECT RAISE(ABORT, 'fixture input failure'); END");
    }

    protected void removeInputFailure(JdbcTemplate jdbc) {
        jdbc.execute("DROP TRIGGER redaction_input_failure");
    }

    private Fixture fixture(String... properties) throws Exception {
        List<String> args = new ArrayList<>(List.of(
                "--spring.config.location=classpath:/application.yml",
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
                "--logging.level.root=WARN"));
        args.addAll(databaseArguments(tempDir.resolve(UUID.randomUUID() + ".db")));
        args.addAll(List.of(properties));

        return new Fixture((ServletWebServerApplicationContext)
                new SpringApplicationBuilder(SbaAgenticApplication.class).run(args.toArray(String[]::new)));
    }

    private static class Fixture implements AutoCloseable {
        final ServletWebServerApplicationContext app;
        final HttpClient client = HttpClient.newHttpClient();
        final ObjectMapper mapper;
        final JdbcTemplate jdbc;
        final String base;

        Fixture(ServletWebServerApplicationContext app) {
            this.app = app;
            mapper = app.getBean(ObjectMapper.class);
            jdbc = app.getBean(JdbcTemplate.class);
            base = "http://127.0.0.1:" + app.getWebServer().getPort();
        }

        ObjectNode request(boolean unassigned) throws Exception {
            String project = "/fixture/meld-redaction-" + UUID.randomUUID();
            List<String> sessions = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                sessions.add(http(
                                "POST",
                                "/api/events",
                                Map.of(
                                        "source",
                                        "manual",
                                        "clientSessionId",
                                        UUID.randomUUID().toString(),
                                        "cwd",
                                        unassigned && i == 1 ? project + "-other" : project,
                                        "eventType",
                                        "Observation",
                                        "text",
                                        "Original evidence"),
                                200)
                        .path("sessionId")
                        .asText());
            }
            ObjectNode request = mapper.createObjectNode();
            if (!unassigned) request.put("projectKey", ProjectKey.of(project).encoded());
            request.put("title", "Saved synthesis");
            request.put("body", "Ordinary body");
            request.put("executionMode", "export_bundle");
            request.set("sessionIds", mapper.valueToTree(sessions.reversed()));
            request.putObject("metadata").put("kind", "braid");

            return request;
        }

        JsonNode saveAndCheck(ObjectNode request) throws Exception {
            JsonNode saved = http("POST", "/api/melds", request, 200);
            String id = saved.path("id").asText();
            assertThat(http("GET", "/api/melds/" + id, null, 200)).isEqualTo(saved);
            Map<String, Object> row = jdbc.queryForMap("SELECT * FROM session_melds WHERE id=?", id);
            for (String field : List.of("title", "body", "provider", "model"))
                assertThat(row.get(field)).isEqualTo(saved.path(field).asText());
            assertThat(row.get("prompt_version"))
                    .isEqualTo(saved.path("promptVersion").asText());
            assertThat(mapper.readTree(row.get("metadata_json").toString())).isEqualTo(saved.path("metadata"));
            List<String> selected = new ArrayList<>();
            request.path("sessionIds").forEach(value -> selected.add(value.asText()));
            assertThat(saved.path("sessions").findValuesAsText("id")).containsExactlyElementsOf(selected);
            for (JsonNode session : saved.path("sessions")) {
                Map<String, Object> source = jdbc.queryForMap(
                        "SELECT source,client_session_id,cwd FROM agent_sessions WHERE id=?",
                        session.path("id").asText());
                assertThat(session.path("source").asText()).isEqualTo(source.get("source"));
                assertThat(session.path("clientSessionId").asText()).isEqualTo(source.get("client_session_id"));
                assertThat(session.path("cwd").asText()).isEqualTo(source.get("cwd"));
                if ("braid".equals(row.get("artifact_kind"))) {
                    JsonNode snapshot = mapper.readTree(jdbc.queryForObject(
                            "SELECT metadata_json FROM session_meld_inputs WHERE meld_id=? AND session_id=?",
                            String.class,
                            id,
                            session.path("id").asText()));
                    assertThat(snapshot.path("source")).isEqualTo(session.path("source"));
                    assertThat(snapshot.path("clientSessionId")).isEqualTo(session.path("clientSessionId"));
                    assertThat(snapshot.path("cwd")).isEqualTo(session.path("cwd"));
                }
            }
            assertThat(saved.path("projectKey"))
                    .isEqualTo(request.has("projectKey") ? request.path("projectKey") : mapper.nullNode());
            assertThat(row.get("artifact_kind"))
                    .isEqualTo(request.path("metadata").path("kind").asText().equals("braid") ? "braid" : "meld");

            return saved;
        }

        JsonNode http(String method, String path, Object body, int expected) throws Exception {
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

        @Override
        public void close() {
            app.close();
            client.close();
        }
    }
}
