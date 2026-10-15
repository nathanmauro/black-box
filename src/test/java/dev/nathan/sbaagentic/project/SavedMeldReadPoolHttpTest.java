package dev.nathan.sbaagentic.project;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import dev.nathan.sbaagentic.SbaAgenticApplication;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A saved meld or braid is read together with its ordered source sessions, and each such read has
 * to finish on one pooled connection at a time. When reading the sources borrowed a second
 * connection while the first still held the meld rows, readers that together took every pooled
 * connection waited on each other, and all other requests, captures included, failed when the
 * pool's connection timeout ran out. The sources now load in batches after the meld rows.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SavedMeldReadPoolHttpTest {
    /** A starved request fails after one second instead of Hikari's 30-second default. */
    private static final Duration CONNECTION_TIMEOUT = Duration.ofSeconds(1);

    @TempDir
    static Path tempDir;

    private final HttpClient client = HttpClient.newHttpClient();
    private ServletWebServerApplicationContext app;
    private ObjectMapper mapper;
    private HikariDataSource pool;
    private String base;
    private String mcpSession;
    private int rpcId;

    private String marker;
    private String projectKey;
    private List<String> sources;
    private String assignedBraid;
    private String unassignedBraid;

    protected List<String> databaseArguments() throws Exception {

        return List.of("--spring.datasource.url=jdbc:sqlite:" + tempDir.resolve("meld-reads.db"));
    }

    protected void cleanupDatabase() throws Exception {}

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
                "--spring.datasource.hikari.connection-timeout=" + CONNECTION_TIMEOUT.toMillis(),
                "--spring.main.banner-mode=off",
                "--logging.level.root=WARN"));
        args.addAll(databaseArguments());
        app = (ServletWebServerApplicationContext)
                new SpringApplicationBuilder(SbaAgenticApplication.class).run(args.toArray(String[]::new));
        mapper = app.getBean(ObjectMapper.class);
        pool = app.getBean(HikariDataSource.class);
        assertThat(pool.getConnectionTimeout()).isEqualTo(CONNECTION_TIMEOUT.toMillis());
        base = "http://127.0.0.1:" + app.getWebServer().getPort();
        rpc(
                "initialize",
                Map.of(
                        "protocolVersion",
                        "2024-11-05",
                        "capabilities",
                        Map.of(),
                        "clientInfo",
                        Map.of("name", "meld-read-pool", "version", "1")));

        marker = "meld-reads-" + UUID.randomUUID();
        String project = "/fixture/" + marker;
        projectKey = ProjectKey.of(project).encoded();
        // Descending ids, so the listed order can only come from the saved input order.
        sources = Stream.of(seed(project), seed(project), seed(project))
                .sorted(Comparator.reverseOrder())
                .toList();
        assignedBraid = saveBraid(marker, sources, projectKey);
        unassignedBraid = saveBraid(marker, sources, null);
    }

    @AfterAll
    void stop() throws Exception {
        try {
            if (app != null) app.close();
            client.close();
        } finally {
            cleanupDatabase();
        }
    }

    Stream<Named<Executable>> savedMeldReads() {

        return Stream.of(
                Named.of("saved meld by id", () -> {
                    JsonNode meld = get("/api/melds/" + assignedBraid);
                    assertThat(ids(meld.path("sessions"), "id")).containsExactlyElementsOf(sources);
                }),
                Named.of("unassigned braid list", () -> {
                    JsonNode list = get("/api/melds?kind=braid&scope=unassigned&limit=100");
                    JsonNode braid = item(list.path("items"), "id", unassignedBraid);
                    assertThat(ids(braid.path("sessions"), "id")).containsExactlyElementsOf(sources);
                }),
                Named.of("project melds", () -> {
                    JsonNode melds = get("/api/projects/" + projectKey + "/melds");
                    JsonNode braid = item(melds, "id", assignedBraid);
                    assertThat(ids(braid.path("sessions"), "id")).containsExactlyElementsOf(sources);
                }),
                Named.of("project timeline", () -> {
                    JsonNode timeline = get("/api/projects/" + projectKey + "/timeline");
                    JsonNode block = item(timeline.path("items"), "id", assignedBraid);
                    assertThat(ids(block.path("sourceSessions"), "id")).containsExactlyElementsOf(sources);
                }),
                Named.of("project graph", () -> {
                    JsonNode graph = get("/api/projects/" + projectKey + "/graph");
                    assertThat(graph.path("captures").findValuesAsText("id")).contains(assignedBraid);
                }),
                Named.of("findBraids over MCP", () -> {
                    JsonNode found = findBraids(Map.of("query", marker));
                    assertThat(found.path("status").asText())
                            .as(found.toString())
                            .isEqualTo("ok");
                    assertThat(found.path("items").findValuesAsText("artifactId"))
                            .containsExactlyInAnyOrder(assignedBraid, unassignedBraid);
                    for (JsonNode hit : found.path("items"))
                        assertThat(ids(hit.path("sessions"), "sessionId")).containsExactlyElementsOf(sources);
                }));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("savedMeldReads")
    void readFinishesWhileTheRestOfThePoolIsBusy(Executable read) throws Throwable {
        List<Connection> held = new ArrayList<>();
        try {
            while (held.size() < pool.getMaximumPoolSize() - 1) held.add(pool.getConnection());
            read.execute();
        } finally {
            for (Connection connection : held) connection.close();
        }
    }

    @Test
    void braidListsAsConcurrentAsThePoolLeaveRoomForACapture() throws Exception {
        String project = "/fixture/meld-list-" + UUID.randomUUID();
        List<String> braidSources = List.of(seed(project), seed(project));
        for (int i = 0; i < 25; i++) saveBraid("Listed braid " + i, braidSources, null);
        int readers = pool.getMaximumPoolSize();
        ExecutorService executor = Executors.newFixedThreadPool(readers);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<List<Integer>>> lists = new ArrayList<>();
            for (int i = 0; i < readers; i++)
                lists.add(executor.submit(() -> {
                    start.await();
                    List<Integer> statuses = new ArrayList<>();
                    for (int round = 0; round < 3; round++)
                        statuses.add(send("GET", "/api/melds?kind=braid&scope=unassigned&limit=20", null)
                                .statusCode());

                    return statuses;
                }));
            start.countDown();
            HttpResponse<String> capture = send("POST", "/api/events", observation(project));
            for (Future<List<Integer>> list : lists)
                assertThat(list.get(60, TimeUnit.SECONDS)).containsOnly(200);
            assertThat(capture.statusCode()).as(capture.body()).isEqualTo(200);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void projectMeldsSpanningSeveralInputBatchesKeepTheirOwnOrderedSources() throws Exception {
        String project = "/fixture/meld-batches-" + UUID.randomUUID();
        List<String> forward = List.of(seed(project), seed(project));
        Map<String, List<String>> expected = new LinkedHashMap<>();
        List<Object[]> melds = new ArrayList<>();
        List<Object[]> inputs = new ArrayList<>();
        // More melds than two input batches hold; stored directly so the fixture stays fast.
        for (int i = 0; i < 1001; i++) {
            String id = "batched-" + i + "-" + UUID.randomUUID();
            List<String> order = i % 2 == 0 ? forward : forward.reversed();
            expected.put(id, order);
            String createdAt =
                    Instant.parse("2026-01-01T00:00:00Z").plusSeconds(i).toString();
            melds.add(new Object[] {
                id, project, "meld", "Batched " + i, "Body", "local", "none", "v1", "export_bundle", 0, null, createdAt
            });
            for (int position = 0; position < order.size(); position++)
                inputs.add(new Object[] {id, order.get(position), position, 0, null});
        }
        JdbcTemplate jdbc = app.getBean(JdbcTemplate.class);
        new TransactionTemplate(app.getBean(PlatformTransactionManager.class)).executeWithoutResult(status -> {
            jdbc.batchUpdate(
                    "INSERT INTO session_melds (id, project_key, artifact_kind, title, body, provider, model,"
                            + " prompt_version, execution_mode, saved_from_preview, metadata_json, created_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    melds);
            jdbc.batchUpdate(
                    "INSERT INTO session_meld_inputs (meld_id, session_id, input_order, included_summary, metadata_json)"
                            + " VALUES (?, ?, ?, ?, ?)",
                    inputs);
        });

        Map<String, List<String>> listed = new LinkedHashMap<>();
        for (JsonNode meld : get("/api/projects/" + ProjectKey.of(project).encoded() + "/melds"))
            listed.put(meld.path("id").asText(), ids(meld.path("sessions"), "id"));
        assertThat(listed).isEqualTo(expected);
    }

    private String seed(String project) throws Exception {

        return json(send("POST", "/api/events", observation(project)))
                .path("sessionId")
                .asText();
    }

    private Map<String, Object> observation(String project) {

        return Map.of(
                "source",
                "manual",
                "clientSessionId",
                UUID.randomUUID().toString(),
                "cwd",
                project,
                "eventType",
                "Observation",
                "text",
                "Evidence for a saved braid");
    }

    private String saveBraid(String title, List<String> sessions, String projectKey) throws Exception {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("title", title);
        request.put("body", "A saved artifact, without a model call");
        request.put("executionMode", "export_bundle");
        request.put("sessionIds", sessions);
        request.put("metadata", Map.of("kind", "braid"));
        if (projectKey != null) request.put("projectKey", projectKey);

        return json(send("POST", "/api/melds", request)).path("id").asText();
    }

    private static List<String> ids(JsonNode sessions, String field) {

        return StreamSupport.stream(sessions.spliterator(), false)
                .map(session -> session.path(field).asText())
                .toList();
    }

    private static JsonNode item(JsonNode items, String field, String id) {

        return StreamSupport.stream(items.spliterator(), false)
                .filter(item -> id.equals(item.path(field).asText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(id + " is not listed in " + items));
    }

    private JsonNode get(String path) throws Exception {

        return json(send("GET", path, null));
    }

    private JsonNode findBraids(Map<String, Object> arguments) throws Exception {
        JsonNode result = rpc("tools/call", Map.of("name", "findBraids", "arguments", arguments));
        assertThat(result.path("isError").asBoolean()).as(result.toString()).isFalse();

        return mapper.readTree(result.path("content").get(0).path("text").asText());
    }

    private JsonNode rpc(String method, Map<String, Object> params) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + "/mcp"))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream");
        if (mcpSession != null) request.header("Mcp-Session-Id", mcpSession);
        HttpResponse<String> response = client.send(
                request.POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(
                                Map.of("jsonrpc", "2.0", "id", ++rpcId, "method", method, "params", params))))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        response.headers().firstValue("Mcp-Session-Id").ifPresent(session -> mcpSession = session);
        String content = response.body();
        if (response.headers().firstValue("Content-Type").orElse("").contains("text/event-stream"))
            content = content.lines()
                    .filter(line -> line.startsWith("data:"))
                    .map(line -> line.substring(5).strip())
                    .findFirst()
                    .orElseThrow();
        JsonNode reply = mapper.readTree(content);
        assertThat(reply.has("error")).as(reply.toString()).isFalse();

        return reply.path("result");
    }

    private JsonNode json(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);

        return mapper.readTree(response.body());
    }

    private HttpResponse<String> send(String method, String path, Object body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .method(
                        method,
                        body == null
                                ? HttpRequest.BodyPublishers.noBody()
                                : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();

        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
