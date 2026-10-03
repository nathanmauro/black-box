package dev.nathan.sbaagentic.project;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nathan.sbaagentic.SbaAgenticApplication;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/** Real save/read requests against private relational storage; providers stay disabled. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProjectlessBraidHttpTest {
    @TempDir
    static Path tempDir;

    protected final HttpClient client = HttpClient.newHttpClient();
    protected ServletWebServerApplicationContext app;
    protected ObjectMapper mapper;
    protected JdbcTemplate jdbc;
    protected String base;
    private boolean legacyPrepared;

    protected String schemaResource() {

        return "schema.sql";
    }

    protected Connection fixtureConnection() throws Exception {

        return DriverManager.getConnection("jdbc:sqlite:" + tempDir.resolve("braids.db"));
    }

    protected void prepareLegacyStorage() throws Exception {
        if (legacyPrepared)

            return;
        try (Connection connection = fixtureConnection()) {
            var dataSource = new SingleConnectionDataSource(connection, true);
            String current = new ClassPathResource(schemaResource()).getContentAsString(StandardCharsets.UTF_8);
            String legacy = current.replaceFirst(
                    "(?s)CREATE TABLE IF NOT EXISTS session_melds \\(.*?\\);", MeldMigrationContract.OLD + ";");
            assertThat(legacy).isNotEqualTo(current);
            new ResourceDatabasePopulator(new ByteArrayResource(legacy.getBytes(StandardCharsets.UTF_8)))
                    .execute(dataSource);
            JdbcTemplate storage = new JdbcTemplate(dataSource);
            storage.update(
                    "INSERT INTO agent_sessions (id,source,client_session_id,title,cwd,started_at,last_seen_at,event_count) "
                            + "VALUES(?,?,?,?,?,?,?,?)",
                    "legacy-session",
                    "manual",
                    "legacy-client",
                    "Legacy session",
                    "/fixture/legacy",
                    "2026-01-01T00:00:00Z",
                    "2026-01-01T00:00:00Z",
                    0);
            for (String kind : List.of("meld", "braid")) {
                storage.update(
                        "INSERT INTO session_melds VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                        "legacy-" + kind,
                        "/fixture/legacy",
                        "Legacy " + kind,
                        "Original legacy body",
                        "local",
                        "none",
                        "v1",
                        "export_bundle",
                        1,
                        "{ \"kind\": \"" + kind + "\", \"opaque\": null }",
                        "2026-01-01T00:00:00.000000001Z");
                storage.update(
                        "INSERT INTO session_meld_inputs VALUES(?,?,?,?,?)",
                        "legacy-" + kind,
                        "legacy-session",
                        0,
                        0,
                        "null");
            }
            storage.update(
                    "INSERT INTO session_meld_inputs VALUES(?,?,?,?,?)",
                    "legacy-braid",
                    "legacy-orphan",
                    1,
                    0,
                    "{\"provenanceVersion\":1,\"source\":{},\"clientSessionId\":9,\"cwd\":false}");
        }
        legacyPrepared = true;
    }

    protected List<String> databaseArguments() throws Exception {
        prepareLegacyStorage();

        return List.of("--spring.datasource.url=jdbc:sqlite:" + tempDir.resolve("braids.db"));
    }

    @BeforeAll
    void start() throws Exception {
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
        args.addAll(databaseArguments());
        app = (ServletWebServerApplicationContext)
                new SpringApplicationBuilder(SbaAgenticApplication.class).run(args.toArray(String[]::new));
        mapper = app.getBean(ObjectMapper.class);
        jdbc = app.getBean(JdbcTemplate.class);
        base = "http://127.0.0.1:" + app.getWebServer().getPort();
    }

    protected void cleanupDatabase() throws Exception {}

    @AfterAll
    void stop() throws Exception {
        try {
            if (app != null) app.close();
            client.close();
        } finally {
            cleanupDatabase();
        }
    }

    @Test
    void legacyProjectMeldsAndArbitraryInputMetadataSurviveStartupAndRestart() throws Exception {
        List<Map<String, Object>> before = jdbc.queryForList(
                "SELECT * FROM session_meld_inputs WHERE meld_id LIKE 'legacy-%' ORDER BY meld_id,input_order");
        for (String kind : List.of("meld", "braid")) {
            JsonNode saved = http("GET", "/api/melds/legacy-" + kind, null, 200);
            assertThat(saved.path("canonicalKey").asText()).isEqualTo("/fixture/legacy");
            assertThat(saved.path("metadata").path("kind").asText()).isEqualTo(kind);
            assertThat(jdbc.queryForObject(
                            "SELECT artifact_kind FROM session_melds WHERE id=?", String.class, "legacy-" + kind))
                    .isEqualTo(kind);
            assertThat(saved.path("sessions").get(0).path("clientSessionId").asText())
                    .isEqualTo("legacy-client");
            if (kind.equals("braid")) {
                assertThat(saved.path("sessions").get(1).path("id").asText()).isEqualTo("legacy-orphan");
                assertThat(saved.path("sessions").get(1).path("startedAt").isNull())
                        .isTrue();
            }
        }
        app.close();
        start();
        http("GET", "/api/melds/legacy-braid", null, 200);
        assertThat(jdbc.queryForList(
                        "SELECT * FROM session_meld_inputs WHERE meld_id LIKE 'legacy-%' ORDER BY meld_id,input_order"))
                .isEqualTo(before);
    }

    @Test
    void missingProjectBraidAcceptsTwoExistingCrossProjectSessions() throws Exception {
        String key = UUID.randomUUID().toString();
        String projectA = "/fixture/braid-a-" + key;
        String projectB = "/fixture/braid-b-" + key;
        JsonNode first = seed(projectA);
        JsonNode second = seed(projectB);
        List<String> sessions = List.of(
                second.path("sessionId").asText(), first.path("sessionId").asText());
        Map<String, Object> request = braid(List.of(" " + sessions.get(0) + " ", sessions.get(1), sessions.get(0)));
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("kind", "braid");
        metadata.put("evidenceIds", List.of("opaque-unverified-reference"));
        metadata.put("optional", null);
        metadata.put("nested", Map.of("claims", List.of("caller-owned")));
        request.put("metadata", metadata);
        JsonNode projects = http("GET", "/api/projects", null, 200);
        Map<String, JsonNode> views = new LinkedHashMap<>();
        for (String project : List.of(projectA, projectB)) {
            for (String suffix : List.of("/timeline", "/graph", "/melds")) {
                String path = "/api/projects/" + ProjectKey.of(project).encoded() + suffix;
                views.put(path, stableView(http("GET", path, null, 200)));
            }
        }
        var events = jdbc.queryForList("SELECT * FROM agent_events ORDER BY id");
        JsonNode saved = http("POST", "/api/melds", request, 200);
        assertThat(saved.path("projectKey").isNull()).isTrue();
        assertThat(saved.path("canonicalKey").isNull()).isTrue();
        assertThat(saved.path("sessions").findValuesAsText("id")).containsExactlyElementsOf(sessions);
        assertThat(saved.path("metadata")).isEqualTo(mapper.valueToTree(metadata));
        String id = saved.path("id").asText();
        assertThat(http("GET", "/api/melds/" + id, null, 200)).isEqualTo(saved);
        assertThat(jdbc.queryForObject("SELECT artifact_kind FROM session_melds WHERE id=?", String.class, id))
                .isEqualTo("braid");
        assertThat(jdbc.queryForObject("SELECT project_key FROM session_melds WHERE id=?", String.class, id))
                .isNull();
        var rows = jdbc.queryForList(
                "SELECT session_id,metadata_json FROM session_meld_inputs WHERE meld_id=? ORDER BY input_order", id);
        assertThat(rows).hasSize(2);
        for (int i = 0; i < rows.size(); i++) {
            JsonNode snapshot = mapper.readTree((String) rows.get(i).get("metadata_json"));
            JsonNode original = i == 0 ? second : first;
            assertThat(rows.get(i).get("session_id")).isEqualTo(sessions.get(i));
            assertThat(snapshot.path("source").asText())
                    .isEqualTo(original.path("source").asText());
            assertThat(snapshot.path("clientSessionId").asText())
                    .isEqualTo(original.path("clientSessionId").asText());
            assertThat(snapshot.path("cwd").asText()).isEqualTo(i == 0 ? projectB : projectA);
        }
        assertThat(http("GET", "/api/projects", null, 200)).isEqualTo(projects);
        for (var view : views.entrySet())
            assertThat(stableView(http("GET", view.getKey(), null, 200))).isEqualTo(view.getValue());
        assertThat(jdbc.queryForList("SELECT * FROM agent_events ORDER BY id")).isEqualTo(events);
    }

    @Test
    void explicitNullAndEightSessionsWorkAndProvenanceSurvivesSessionMoveAndRestart() throws Exception {
        List<JsonNode> events = new ArrayList<>();
        List<String> sessions = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            JsonNode event = seed("/fixture/braid-max-" + UUID.randomUUID());
            events.add(event);
            sessions.add(event.path("sessionId").asText());
        }
        var request = braid(sessions);
        request.put("projectKey", null);
        JsonNode saved = http("POST", "/api/melds", request, 200);
        assertThat(saved.path("sessions")).hasSize(8);
        String id = saved.path("id").asText();
        JsonNode original =
                http("GET", "/api/events/" + events.get(0).path("eventId").asText(), null, 200);
        http(
                "POST",
                "/api/events",
                Map.of(
                        "source",
                        "manual",
                        "clientSessionId",
                        events.get(0).path("clientSessionId").asText(),
                        "eventType",
                        "Observation",
                        "cwd",
                        "/fixture/moved",
                        "text",
                        "Later session location"),
                200);
        JsonNode moved = http("GET", "/api/melds/" + id, null, 200);
        assertThat(moved.path("sessions").get(0).path("cwd"))
                .isEqualTo(saved.path("sessions").get(0).path("cwd"));
        assertThat(http("GET", "/api/events/" + original.path("id").asText(), null, 200))
                .isEqualTo(original);
        app.close();
        start();
        assertThat(http("GET", "/api/melds/" + id, null, 200)).isEqualTo(moved);
    }

    @Test
    void invalidUnassignedRequestsNeverAddParentOrInputs() throws Exception {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 9; i++)
            ids.add(seed("/fixture/braid-invalid-" + UUID.randomUUID())
                    .path("sessionId")
                    .asText());
        List<Map<String, Object>> invalid = new ArrayList<>();
        for (Object kind : List.of("Braid", " braid", "meld", 1, true, List.of("braid"))) {
            var request = braid(ids.subList(0, 2));
            request.put("metadata", Map.of("kind", kind));
            invalid.add(request);
        }
        for (boolean omit : List.of(true, false)) {
            var request = braid(ids.subList(0, 2));
            if (omit) request.remove("metadata");
            else request.put("metadata", null);
            invalid.add(request);
        }
        for (List<String> selected : List.of(
                List.<String>of(),
                ids.subList(0, 1),
                List.of(ids.get(0), ids.get(0)),
                List.of(ids.get(0), "missing-" + UUID.randomUUID()),
                ids)) invalid.add(braid(selected));
        var blank = braid(ids.subList(0, 2));
        blank.put("projectKey", " ");
        invalid.add(blank);
        var body = braid(ids.subList(0, 2));
        body.put("body", " ");
        invalid.add(body);
        int parents = count("session_melds"), inputs = count("session_meld_inputs");
        var events = jdbc.queryForList("SELECT * FROM agent_events ORDER BY id");
        for (var request : invalid) http("POST", "/api/melds", request, 400);
        assertThat(count("session_melds")).isEqualTo(parents);
        assertThat(count("session_meld_inputs")).isEqualTo(inputs);
        assertThat(jdbc.queryForList("SELECT * FROM agent_events ORDER BY id")).isEqualTo(events);
    }

    @Test
    void assignedBraidsAndOrdinaryMeldsKeepProjectAndAliasValidation() throws Exception {
        String project = "/fixture/assigned-braid-" + UUID.randomUUID(), alias = project + "-alias";
        JsonNode own = seed(project), sibling = seed(alias), foreign = seed(project + "-foreign");
        http("PUT", "/api/project-aliases", Map.of("aliasKey", alias, "canonicalKey", project), 200);
        var request = braid(List.of(
                own.path("sessionId").asText(), sibling.path("sessionId").asText()));
        request.put("projectKey", ProjectKey.of(alias).encoded());
        JsonNode saved = http("POST", "/api/melds", request, 200);
        assertThat(saved.path("canonicalKey").asText()).isEqualTo(project);
        assertThat(http("GET", "/api/melds/" + saved.path("id").asText(), null, 200))
                .isEqualTo(saved);
        request.put(
                "sessionIds",
                List.of(
                        own.path("sessionId").asText(),
                        foreign.path("sessionId").asText()));
        int parents = count("session_melds"), inputs = count("session_meld_inputs");
        http("POST", "/api/melds", request, 400);
        assertThat(count("session_melds")).isEqualTo(parents);
        assertThat(count("session_meld_inputs")).isEqualTo(inputs);
        request.put("sessionIds", List.of(own.path("sessionId").asText()));
        request.put("metadata", Map.of("kind", "opaque-not-braid"));
        JsonNode ordinary = http("POST", "/api/melds", request, 200);
        assertThat(jdbc.queryForObject(
                        "SELECT artifact_kind FROM session_melds WHERE id=?",
                        String.class,
                        ordinary.path("id").asText()))
                .isEqualTo("meld");
        assertThat(http("GET", "/api/melds/" + ordinary.path("id").asText(), null, 200))
                .isEqualTo(ordinary);
    }

    @Test
    void unassignedPaginationUsesPreciseTimesAndIdTies() throws Exception {
        List<String> sessions = List.of(
                seed("/fixture/page-a-" + UUID.randomUUID()).path("sessionId").asText(),
                seed("/fixture/page-b-" + UUID.randomUUID()).path("sessionId").asText());
        for (String timestamp : List.of(
                "2099-01-01T00:00:00Z",
                "2099-01-01T00:00:00.1Z",
                "2099-01-01T00:00:00.000000001Z",
                "2099-01-01T00:00:00.1Z",
                "2098-12-31T23:59:59.999999999Z",
                Instant.MIN.toString(),
                Instant.MAX.toString(),
                "-10000-01-01T00:00:00.000000001Z",
                "+10000-01-01T00:00:00Z")) {
            String id =
                    http("POST", "/api/melds", braid(sessions), 200).path("id").asText();
            jdbc.update("UPDATE session_melds SET created_at=? WHERE id=?", timestamp, id);
        }
        List<Map<String, Object>> expected =
                jdbc.queryForList("SELECT id,created_at FROM session_melds WHERE project_key IS NULL");
        expected.sort(
                Comparator.<Map<String, Object>, Instant>comparing(row -> Instant.parse((String) row.get("created_at")))
                        .thenComparing(row -> (String) row.get("id"))
                        .reversed());
        List<String> seen = new ArrayList<>();
        String before = null;
        do {
            JsonNode page = http(
                    "GET",
                    "/api/melds?kind=braid&scope=unassigned&limit=2" + (before == null ? "" : "&before=" + before),
                    null,
                    200);
            assertThat(page.path("items").size()).isBetween(1, 2);
            assertThat(page.path("count").asInt()).isEqualTo(page.path("items").size());
            for (JsonNode item : page.path("items")) seen.add(item.path("id").asText());
            before = page.path("nextBefore").isNull()
                    ? null
                    : page.path("nextBefore").asText();
        } while (before != null);
        assertThat(seen)
                .containsExactlyElementsOf(
                        expected.stream().map(row -> (String) row.get("id")).toList());
        assertThat(new HashSet<>(seen)).hasSize(seen.size());
    }

    @Test
    void unsupportedFiltersAndCursorsAreErrorsAndMissingDetailIs404() throws Exception {
        for (String query : List.of(
                "",
                "?kind=braid",
                "?scope=unassigned",
                "?kind=meld&scope=unassigned",
                "?kind=braid&scope=project",
                "?kind=braid&scope=unassigned&limit=0",
                "?kind=braid&scope=unassigned&limit=101",
                "?kind=braid&scope=unassigned&limit=wat",
                "?kind=braid&scope=unassigned&before=",
                "?kind=braid&scope=unassigned&before=broken",
                "?kind=braid&scope=unassigned&project=anything",
                "?kind=braid&scope=unassigned&kind=braid")) http("GET", "/api/melds" + query, null, 400);
        http("GET", "/api/melds/missing-" + UUID.randomUUID(), null, 404);
    }

    private JsonNode stableView(JsonNode response) {
        if (response.isObject()) ((com.fasterxml.jackson.databind.node.ObjectNode) response).remove("generatedAt");

        return response;
    }

    protected int count(String table) {

        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    protected void installInputFailure() {
        jdbc.execute("CREATE TRIGGER braid_input_failure BEFORE INSERT ON session_meld_inputs WHEN NEW.input_order=1 "
                + "BEGIN SELECT RAISE(ABORT, 'fixture input failure'); END");
    }

    protected void removeInputFailure() {
        jdbc.execute("DROP TRIGGER braid_input_failure");
    }

    @Test
    void failedSecondInputRollsBackTheParentAndFirstInput() throws Exception {
        List<String> sessions = List.of(
                seed("/fixture/failure-a-" + UUID.randomUUID())
                        .path("sessionId")
                        .asText(),
                seed("/fixture/failure-b-" + UUID.randomUUID())
                        .path("sessionId")
                        .asText());
        int parents = count("session_melds"), inputs = count("session_meld_inputs");
        installInputFailure();
        try {
            http("POST", "/api/melds", braid(sessions), 500);
        } finally {
            removeInputFailure();
        }
        assertThat(count("session_melds")).isEqualTo(parents);
        assertThat(count("session_meld_inputs")).isEqualTo(inputs);
    }

    protected JsonNode seed(String project) throws Exception {

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

    protected Map<String, Object> braid(List<String> sessions) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("title", "Cross-project braid");
        request.put("body", "A saved artifact, without a model call");
        request.put("executionMode", "export_bundle");
        request.put("sessionIds", sessions);
        request.put("metadata", Map.of("kind", "braid", "evidenceIds", List.of("opaque-caller-reference")));

        return request;
    }

    protected JsonNode http(String method, String path, Object body, int status) throws Exception {
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
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);

        return mapper.readTree(response.body());
    }
}
