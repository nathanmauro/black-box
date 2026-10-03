package dev.nathan.sbaagentic.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import dev.nathan.sbaagentic.SbaAgenticApplication;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.SpringApplicationJsonEnvironmentPostProcessor;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.SimpleCommandLinePropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;

/** Current consumer APIs composed on one authenticated PostgreSQL server; no cloud or model calls. */
@EnabledIfEnvironmentVariable(named = "SBA_POSTGRES_TEST_URL", matches = "jdbc:postgresql:.+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuthenticatedPostgresConsumerContractTest {
    private final String schema =
            "bb_auth_contract_" + UUID.randomUUID().toString().replace("-", "");
    private final String repo = "/fixture/authenticated-postgres/" + UUID.randomUUID();
    private final String password = UUID.randomUUID().toString() + UUID.randomUUID();
    private final String token = UUID.randomUUID().toString() + UUID.randomUUID();
    private final HttpClient http =
            HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private ServletWebServerApplicationContext app;
    private JdbcTemplate jdbc;
    private String base;
    private String mcpSession;
    private int rpcId;
    private boolean schemaCreated;

    @BeforeAll
    void startOnlyOurDisposableSchema() throws Exception {
        try (Connection connection = connection()) {
            connection.createStatement().execute("CREATE SCHEMA " + schema);
            schemaCreated = true;
        }
        startApp();
    }

    private Connection connection() throws Exception {

        return DriverManager.getConnection(
                System.getenv("SBA_POSTGRES_TEST_URL"),
                System.getenv().getOrDefault("SBA_POSTGRES_TEST_USERNAME", "blackbox_test"),
                System.getenv().getOrDefault("SBA_POSTGRES_TEST_PASSWORD", "blackbox_disposable_test"));
    }

    private void startApp() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("spring.datasource.url", System.getenv("SBA_POSTGRES_TEST_URL"));
        properties.put(
                "spring.datasource.username",
                System.getenv().getOrDefault("SBA_POSTGRES_TEST_USERNAME", "blackbox_test"));
        properties.put(
                "spring.datasource.password",
                System.getenv().getOrDefault("SBA_POSTGRES_TEST_PASSWORD", "blackbox_disposable_test"));
        properties.put("spring.datasource.hikari.data-source-properties.currentSchema", schema);
        properties.put("server.address", "127.0.0.1");
        properties.put("server.port", "0");
        properties.put("server.shutdown", "immediate");
        properties.put("sba.editor.enabled", "false");
        properties.put("sba.local-ai.enabled", "false");
        properties.put("sba.summary.backend", "local");
        properties.put("sba.elasticsearch.enabled", "false");
        properties.put("sba.memory.embedding.enabled", "false");
        properties.put("sba.ask.embedding-enabled", "false");
        properties.put("SBA_AUTH_ENABLED", "true");
        properties.put("SBA_AUTH_PASSWORD", password);
        properties.put("SBA_AUTH_API_TOKEN", token);
        properties.put("SBA_AUTH_SECURE_COOKIES", "true");
        properties.put("spring.main.banner-mode", "off");
        properties.put("logging.level.root", "WARN");
        // Credentials stay in process memory rather than appearing in command arguments or logs.
        app = (ServletWebServerApplicationContext) new SpringApplicationBuilder(SbaAgenticApplication.class)
                .profiles("postgres")
                .initializers(context -> context.getEnvironment()
                        .getPropertySources()
                        .addFirst(new MapPropertySource("authenticated-postgres-fixture", properties)))
                .run("--spring.config.location=classpath:/application.yml");
        jdbc = app.getBean(JdbcTemplate.class);
        base = "http://127.0.0.1:" + app.getWebServer().getPort();
        mcpSession = null;
    }

    @AfterAll
    void closeAndDropOnlyOurSchema() throws Exception {
        try {
            if (app != null) app.close();
            http.close();
        } finally {
            if (schemaCreated) {
                try (Connection connection = connection()) {
                    connection.createStatement().execute("DROP SCHEMA " + schema + " CASCADE");
                }
            }
        }
    }

    @Test
    void springPoolBindingCanOverrideBaseConnectionSoCloudGuardRejectsTheWholeNamespace() {
        // Boot binds spring.datasource.hikari after building the base datasource. These exact
        // process-environment keys can therefore bypass a check of SBA_DATASOURCE_URL alone.
        // cloud_entrypoint_test.py verifies they all stop startup before Java; this proves why.
        var environment = new StandardEnvironment();
        environment
                .getPropertySources()
                .addFirst(new SystemEnvironmentPropertySource(
                        "pool-override-fixture-systemEnvironment",
                        Map.of(
                                "SPRING_DATASOURCE_HIKARI_JDBCURL", "jdbc:postgresql://other.invalid/other",
                                "SPRING_DATASOURCE_HIKARI_USERNAME", "other-user",
                                "SPRING_DATASOURCE_HIKARI_PASSWORD", "other-fixture-password",
                                "SPRING_DATASOURCE_HIKARI_DRIVERCLASSNAME", "org.sqlite.JDBC")));
        try (var dataSource = new HikariDataSource()) {
            dataSource.setJdbcUrl("jdbc:postgresql://validated.invalid/validated");
            dataSource.setUsername("validated-user");
            dataSource.setPassword("validated-fixture-password");
            dataSource.setDriverClassName("org.postgresql.Driver");
            Binder.get(environment).bind("spring.datasource.hikari", Bindable.ofInstance(dataSource));
            assertThat(dataSource.getJdbcUrl()).isEqualTo("jdbc:postgresql://other.invalid/other");
            assertThat(dataSource.getUsername()).isEqualTo("other-user");
            assertThat(dataSource.getPassword()).isEqualTo("other-fixture-password");
            assertThat(dataSource.getDriverClassName()).isEqualTo("org.sqlite.JDBC");
        }
    }

    @Test
    void relaxedJsonEnvironmentAliasesCanOverridePoolConnectionBeforeTheGuard() {
        for (String name : List.of("SPRING_APPLICATION_JSON", "spring.application.json", "spring_application_json")) {
            var environment = new StandardEnvironment();
            environment
                    .getPropertySources()
                    .addFirst(
                            new SystemEnvironmentPropertySource(
                                    "json-fixture-systemEnvironment",
                                    Map.of(
                                            name,
                                            "{\"spring\":{\"datasource\":{\"hikari\":{\"jdbc-url\":\"jdbc:postgresql://json.invalid/other\"}}}}")));
            new SpringApplicationJsonEnvironmentPostProcessor()
                    .postProcessEnvironment(environment, new SpringApplication(SbaAgenticApplication.class));
            try (var dataSource = new HikariDataSource()) {
                dataSource.setJdbcUrl("jdbc:postgresql://validated.invalid/validated");
                Binder.get(environment).bind("spring.datasource.hikari", Bindable.ofInstance(dataSource));
                assertThat(dataSource.getJdbcUrl()).as(name).isEqualTo("jdbc:postgresql://json.invalid/other");
            }
        }
    }

    @Test
    void classpathOnlyConfigurationExcludesMountedOverridesAndLoadsPostgresProfile(@TempDir Path directory)
            throws Exception {
        Files.createDirectories(directory.resolve("config"));
        Files.writeString(
                directory.resolve("config/application.properties"),
                "spring.datasource.hikari.jdbc-url=jdbc:postgresql://mounted.invalid/other\n");
        var resources = new DefaultResourceLoader();
        // Map Boot's default working-directory lookups onto a temporary fixture directory.
        resources.addProtocolResolver((location, loader) -> (location.startsWith("file:")
                        && !location.startsWith("file:/"))
                ? new FileSystemResource(
                        directory.resolve(location.substring("file:".length())).toString()
                                + (location.endsWith("/") ? "/" : ""))
                : null);
        var defaults = new StandardEnvironment();
        ConfigDataEnvironmentPostProcessor.applyTo(defaults, resources, null, "postgres");
        assertThat(defaults.getProperty("spring.datasource.hikari.jdbc-url"))
                .isEqualTo("jdbc:postgresql://mounted.invalid/other");
        var pinned = new StandardEnvironment();
        pinned.getPropertySources()
                .addFirst(new SimpleCommandLinePropertySource(
                        "--spring.config.location=classpath:/application.yml", "--spring.profiles.active=postgres"));
        ConfigDataEnvironmentPostProcessor.applyTo(pinned, resources, null);
        assertThat(pinned.getProperty("spring.datasource.hikari.jdbc-url")).isNull();
        assertThat(pinned.getProperty("spring.datasource.driver-class-name")).isEqualTo("org.postgresql.Driver");
        assertThat(pinned.getProperty("sba.storage.backend")).isEqualTo("postgres");
    }

    @Test
    void authenticatedConsumersPreserveCaptureEvidenceAndContinuityAcrossRestart() throws Exception {
        assertAnonymousAndWrongBearerCannotReadOrWrite();
        String payload = "full-output-" + "x".repeat(12_000);
        Map<String, Object> input = Map.of("command", "printf fixture", "note", "input retained");
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("source", "manual");
        event.put("clientSessionId", "consumer-fixture");
        event.put("eventType", "PostToolUse");
        event.put("role", "tool");
        event.put("text", "Recorded tool evidence");
        event.put("cwd", repo);
        event.put("toolName", "Bash");
        event.put("toolInput", input);
        event.put("toolOutput", Map.of("stdout", payload, "exitCode", 0));
        event.put("metadata", Map.of("agentId", "agent-fixture", "captureDigest", "digest-fixture"));
        event.put("observedAt", Instant.now().toString());
        Map<String, Object> receipt = Map.of("captureId", UUID.randomUUID().toString(), "event", event);

        String eventId;
        String sessionId;
        var stream = http.send(
                request("/api/stream", token)
                        .header("Accept", "text/event-stream")
                        .build(),
                HttpResponse.BodyHandlers.ofInputStream());
        assertThat(stream.statusCode()).isEqualTo(200);
        assertThat(stream.headers().allValues("Set-Cookie")).isEmpty();
        var lines = new LinkedBlockingQueue<String>();
        var readerPool = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "authenticated-pg-stream-fixture");
            thread.setDaemon(true);

            return thread;
        });
        try {
            readerPool.submit(() -> {
                try (var reader = new BufferedReader(new InputStreamReader(stream.body(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) lines.add(line);
                } catch (java.io.IOException expectedOnClose) {
                    // The test closes the response to release the streaming reader.
                }
            });
            assertThat(lines.poll(5, TimeUnit.SECONDS)).isEqualTo(":connected");
            JsonNode captured = post("/api/events/idempotent", receipt);
            eventId = captured.path("eventId").asText();
            sessionId = captured.path("sessionId").asText();
            assertThat(eventId).isNotBlank();
            assertThat(captured.path("replayed").asBoolean()).isFalse();
            JsonNode replayed = post("/api/events/idempotent", receipt);
            assertThat(replayed.path("eventId").asText()).isEqualTo(eventId);
            assertThat(replayed.path("replayed").asBoolean()).isTrue();
            awaitHeartbeatAndEvent(lines, eventId);
        } finally {
            stream.body().close();
            readerPool.shutdownNow();
            assertThat(readerPool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(countEvents()).isEqualTo(1);
        assertFullEvent(get("/api/events/" + eventId), payload, input);
        JsonNode feedItem = findById(get("/api/events?limit=10").path("items"), "id", eventId);
        assertThat(feedItem.path("text").asText()).isEqualTo("Recorded tool evidence");
        assertThat(mapper.readTree(feedItem.path("toolInputJson").asText())).isEqualTo(mapper.valueToTree(input));
        assertThat(feedItem.path("metadata").path("agentId").asText()).isEqualTo("agent-fixture");
        assertThat(feedItem.path("metadata").path("captureDigest").asText()).isEqualTo("digest-fixture");
        assertThat(get("/api/sessions/" + sessionId).path("cwd").asText()).isEqualTo(repo);
        assertThat(get("/api/sessions/" + sessionId + "/links").path("parents").isArray())
                .isTrue();
        assertThat(get("/api/sessions/" + sessionId + "/judgments").isArray()).isTrue();

        initializeMcp();
        JsonNode listed = rpc("tools/list", Map.of()).path("tools");
        List<String> names = new ArrayList<>();
        listed.forEach(tool -> names.add(tool.path("name").asText()));
        // Verify used capabilities, not a frozen tool count: retired workflow tools are irrelevant.
        assertThat(names).contains("captureDecision", "recallContext", "searchContext");
        JsonNode discovered = call("searchContext", Map.of("query", "session:" + sessionId + " Recorded", "limit", 5));
        JsonNode hit = findById(discovered.path("items"), "eventId", eventId);
        assertThat(hit.path("sourceReference").path("eventPath").asText()).isEqualTo("/api/events/" + eventId);
        assertFullEvent(get(hit.path("sourceReference").path("eventPath").asText()), payload, input);
        assertThat(mcpSession).isNotBlank();
        var mcpWithoutBearer = request("/mcp", null)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("Mcp-Session-Id", mcpSession)
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(
                        Map.of("jsonrpc", "2.0", "id", 1000, "method", "tools/list", "params", Map.of()))));
        assertThat(send(mcpWithoutBearer).statusCode()).isEqualTo(401);
        Map<String, Object> decision = new LinkedHashMap<>(Map.of(
                "source",
                "manual",
                "clientSessionId",
                "mcp-consumer",
                "repo",
                repo,
                "decision",
                "Use the original storage choice",
                "rationale",
                "Original evidence",
                "alternatives",
                List.of(),
                "openLoops",
                List.of()));
        String original = call("captureDecision", decision).path("eventId").asText();
        post(
                "/api/decisions",
                Map.of(
                        "source",
                        "manual",
                        "clientSessionId",
                        "other-project",
                        "repo",
                        repo + "-other",
                        "decision",
                        "Use the original storage choice",
                        "rationale",
                        "Unrelated project"));
        JsonNode recalled =
                call("recallContext", Map.of("project", repo, "query", "storage", "kinds", List.of("decision")));
        assertThat(recalled.path("items")).hasSize(1);
        assertThat(recalled.path("items").get(0).path("eventId").asText()).isEqualTo(original);
        decision.put("decision", "Use the revised storage choice");
        decision.put("rationale", "New measured evidence overturns the old choice");
        decision.put("supersedes", original);
        String replacement = call("captureDecision", decision).path("eventId").asText();
        assertThat(get("/api/recall?project=" + encode(repo) + "&query=storage").path("items"))
                .hasSize(1);
        JsonNode current =
                call("recallContext", Map.of("project", repo, "query", "storage", "kinds", List.of("decision")));
        assertThat(current.path("items").get(0).path("supersedesEventId").asText())
                .isEqualTo(original);
        JsonNode history = get("/api/recall?project=" + encode(repo) + "&query=storage&includeSuperseded=true");
        assertThat(history.path("items")).hasSize(2);
        assertThat(findById(history.path("items"), "eventId", original)
                        .path("supersededByEventId")
                        .asText())
                .isEqualTo(replacement);
        assertThat(get("/api/events/" + original)
                        .path("metadata")
                        .path("decision")
                        .asText())
                .isEqualTo("Use the original storage choice");

        long count = countEvents();
        app.close();
        startApp();
        assertThat(countEvents()).isEqualTo(count);
        assertThat(send(request("/api/events/" + eventId, null)).statusCode()).isEqualTo(401);
        assertFullEvent(get("/api/events/" + eventId), payload, input);
        assertThat(post("/api/events/idempotent", receipt).path("replayed").asBoolean())
                .isTrue();
        assertThat(countEvents()).isEqualTo(count);
        initializeMcp();
        JsonNode afterRestart =
                call("recallContext", Map.of("project", repo, "query", "storage", "kinds", List.of("decision")));
        assertThat(afterRestart.path("items")).hasSize(1);
        assertThat(afterRestart.path("items").get(0).path("eventId").asText()).isEqualTo(replacement);
        assertThat(afterRestart.path("items").get(0).path("supersedesEventId").asText())
                .isEqualTo(original);
        JsonNode historyAfterRestart =
                call("recallContext", Map.of("project", repo, "query", "storage", "includeSuperseded", true));
        assertThat(historyAfterRestart.path("items")).hasSize(2);
        assertThat(findById(historyAfterRestart.path("items"), "eventId", original)
                        .path("supersededByEventId")
                        .asText())
                .isEqualTo(replacement);
    }

    private void assertAnonymousAndWrongBearerCannotReadOrWrite() throws Exception {
        long count = countEvents();
        for (String credential : new String[] {null, "wrong-fixture-token"}) {
            for (String path : List.of(
                    "/api/status",
                    "/api/events",
                    "/api/recall",
                    "/api/projects",
                    "/api/stream",
                    "/mcp",
                    "/actuator/metrics")) {
                assertThat(send(request(path, credential)).statusCode())
                        .as(path)
                        .isEqualTo(401);
            }
            var write = request("/api/events", credential)
                    .header("Content-Type", "application/json")
                    .POST(
                            HttpRequest.BodyPublishers.ofString(
                                    "{\"source\":\"manual\",\"clientSessionId\":\"rejected\",\"eventType\":\"Observation\",\"text\":\"must not persist\"}"));
            assertThat(send(write).statusCode()).isEqualTo(401);
        }
        assertThat(countEvents()).isEqualTo(count);
        var health = send(request("/actuator/health/readiness", null));
        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(health.body()).contains("UP").doesNotContain("components", "details");
        assertThat(send(request("/", null)).headers().firstValue("Location")).contains("/login");
    }

    private void awaitHeartbeatAndEvent(LinkedBlockingQueue<String> lines, String eventId) throws Exception {
        boolean heartbeat = false;
        boolean appended = false;
        String eventName = "";
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
        while ((!heartbeat || !appended) && System.nanoTime() < deadline) {
            String line = lines.poll(1, TimeUnit.SECONDS);
            if (line == null) continue;
            if (line.equals(":heartbeat")) heartbeat = true;
            if (line.startsWith("event:")) eventName = line.substring(6).strip();
            if (line.startsWith("data:") && eventName.equals("event.appended")) {
                JsonNode data = mapper.readTree(line.substring(5).strip());
                if (eventId.equals(data.path("id").asText())) appended = true;
            }
        }
        assertThat(heartbeat)
                .as("direct service emitted its scheduled SSE heartbeat")
                .isTrue();
        assertThat(appended)
                .as("capture was delivered to an authenticated SSE consumer")
                .isTrue();
    }

    private void assertFullEvent(JsonNode event, String payload, Map<String, Object> input) throws Exception {
        assertThat(mapper.readTree(event.path("toolInputJson").asText())).isEqualTo(mapper.valueToTree(input));
        assertThat(mapper.readTree(event.path("toolOutputJson").asText())
                        .path("stdout")
                        .asText())
                .isEqualTo(payload);
        assertThat(event.path("metadata").path("agentId").asText()).isEqualTo("agent-fixture");
        assertThat(event.path("metadata").path("captureDigest").asText()).isEqualTo("digest-fixture");
    }

    private void initializeMcp() throws Exception {
        rpc(
                "initialize",
                Map.of(
                        "protocolVersion",
                        "2024-11-05",
                        "capabilities",
                        Map.of(),
                        "clientInfo",
                        Map.of("name", "authenticated-pg-fixture", "version", "1")));
        var initialized = mcpPost(Map.of("jsonrpc", "2.0", "method", "notifications/initialized"));
        assertThat(initialized.statusCode()).isBetween(200, 299);
    }

    private JsonNode call(String name, Map<String, Object> arguments) throws Exception {
        JsonNode result = rpc("tools/call", Map.of("name", name, "arguments", arguments));
        assertThat(result.path("isError").asBoolean()).as(result.toString()).isFalse();

        return mapper.readTree(result.path("content").get(0).path("text").asText());
    }

    private JsonNode rpc(String method, Map<String, Object> params) throws Exception {
        int id = ++rpcId;
        var response = mcpPost(Map.of("jsonrpc", "2.0", "id", id, "method", method, "params", params));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        String body = response.body();
        if (response.headers().firstValue("Content-Type").orElse("").contains("text/event-stream")) {
            body = body.lines()
                    .filter(line -> line.startsWith("data:"))
                    .map(line -> line.substring(5).strip())
                    .findFirst()
                    .orElseThrow();
        }
        JsonNode envelope = mapper.readTree(body);
        assertThat(envelope.path("id").asInt()).isEqualTo(id);
        assertThat(envelope.has("error")).as(envelope.toString()).isFalse();

        return envelope.path("result");
    }

    private HttpResponse<String> mcpPost(Map<String, Object> body) throws Exception {
        var request = request("/mcp", token)
                .header("Accept", "application/json, text/event-stream")
                .header("Content-Type", "application/json");
        if (mcpSession != null) request.header("Mcp-Session-Id", mcpSession);
        var response = send(request.POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))));
        response.headers().firstValue("Mcp-Session-Id").ifPresent(value -> mcpSession = value);
        assertThat(response.headers().allValues("Set-Cookie")).isEmpty();

        return response;
    }

    private HttpRequest.Builder request(String path, String credential) {
        var request = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(30));
        if (credential != null) request.header("Authorization", "Bearer " + credential);

        return request;
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {

        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode get(String path) throws Exception {
        var response = send(request(path, token));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);

        return mapper.readTree(response.body());
    }

    private JsonNode post(String path, Map<String, Object> body) throws Exception {
        var response = send(request(path, token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.headers().allValues("Set-Cookie")).isEmpty();

        return mapper.readTree(response.body());
    }

    private long countEvents() {

        return jdbc.queryForObject("SELECT count(*) FROM agent_events", Long.class);
    }

    private static JsonNode findById(JsonNode items, String field, String value) {
        for (JsonNode item : items) if (value.equals(item.path(field).asText()))

            return item;

        throw new AssertionError("Expected recorded fixture item missing from response");
    }

    private static String encode(String text) {

        return URLEncoder.encode(text, StandardCharsets.UTF_8);
    }
}
