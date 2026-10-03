package dev.nathan.sbaagentic.recording;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nathan.sbaagentic.SbaAgenticApplication;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

/** Fake secrets through the real HTTP ingest, canonical SQLite store, and event read path. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StructuredRedactionHttpTest {
    private static final String SECRET = "FAKE_CAPTURE_SECRET_123456789";
    private static final String PROVIDER_TOKEN = "ghp_abcdefghijklmnopqrstuvwxyz0123456789AB";

    @TempDir
    static Path tempDir;

    private final TestRestTemplate http = new TestRestTemplate();
    private ServletWebServerApplicationContext app;
    private JdbcTemplate jdbc;
    private ObjectMapper mapper;
    private String base;

    @BeforeAll
    void start() {
        app = (ServletWebServerApplicationContext) new SpringApplicationBuilder(SbaAgenticApplication.class)
                .run(
                        "--spring.config.location=classpath:/application.yml",
                        "--spring.datasource.url=jdbc:sqlite:" + tempDir.resolve("redaction.db"),
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
                        "--sba.ingestion.redact-enabled=true",
                        "--spring.main.banner-mode=off",
                        "--logging.level.root=WARN");
        jdbc = app.getBean(JdbcTemplate.class);
        mapper = app.getBean(ObjectMapper.class);
        base = "http://127.0.0.1:" + app.getWebServer().getPort();
    }

    @AfterAll
    void stop() {
        if (app != null) app.close();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void structuredSecretsNeverReachCanonicalStorageOrEventReads(boolean idempotent) throws Exception {
        String clientSession = "structured-redaction-" + UUID.randomUUID();
        Map<String, Object> event = Map.of(
                "source",
                "codex",
                "clientSessionId",
                clientSession,
                "eventType",
                "PostToolUse",
                "cwd",
                "/fixture/redaction",
                "toolName",
                "fixture-tool",
                "text",
                "Synthetic tool result",
                "toolInput",
                Map.of("api_key", SECRET, "query", "readable input"),
                "toolOutput",
                Map.of("steps", List.of(Map.of("password", SECRET, "count", 2)), PROVIDER_TOKEN, "readable output"),
                "metadata",
                Map.of(
                        "credentials",
                        Map.of("access_token", SECRET),
                        "repo",
                        "/fixture/redaction",
                        "agentId",
                        "fixture-agent",
                        "turnId",
                        "fixture-turn"));
        Object payload = idempotent ? Map.of("captureId", UUID.randomUUID().toString(), "event", event) : event;
        String endpoint = base + (idempotent ? "/api/events/idempotent" : "/api/events");
        var captured = http.postForEntity(endpoint, payload, JsonNode.class);
        assertThat(captured.getStatusCode().value()).isEqualTo(200);
        String eventId = captured.getBody().path("eventId").asText();
        assertThat(eventId).isNotBlank();

        Map<String, Object> persisted = jdbc.queryForMap(
                "SELECT tool_input_json, tool_output_json, metadata_json FROM agent_events WHERE id = ?", eventId);
        assertThat(persisted.values().toString()).doesNotContain(SECRET, PROVIDER_TOKEN);
        JsonNode input = mapper.readTree(persisted.get("tool_input_json").toString());
        assertThat(input.path("api_key").asText()).isEqualTo("[REDACTED]");
        assertThat(input.path("query").asText()).isEqualTo("readable input");
        JsonNode output = mapper.readTree(persisted.get("tool_output_json").toString());
        assertThat(output.path("steps").get(0).path("password").asText()).isEqualTo("[REDACTED]");
        assertThat(output.path("steps").get(0).path("count").asInt()).isEqualTo(2);
        assertThat(output.path("[REDACTED]").asText()).isEqualTo("readable output");
        JsonNode metadata = mapper.readTree(persisted.get("metadata_json").toString());
        assertThat(metadata.path("credentials").asText()).isEqualTo("[REDACTED]");
        assertThat(metadata.path("repo").asText()).isEqualTo("/fixture/redaction");
        assertThat(metadata.path("agentId").asText()).isEqualTo("fixture-agent");
        assertThat(metadata.path("turnId").asText()).isEqualTo("fixture-turn");

        var fetched = http.getForEntity(base + "/api/events/" + eventId, JsonNode.class);
        assertThat(fetched.getStatusCode().value()).isEqualTo(200);
        assertThat(fetched.getBody().toString()).doesNotContain(SECRET, PROVIDER_TOKEN);
        assertThat(fetched.getBody().path("clientSessionId").asText()).isEqualTo(clientSession);
        assertThat(fetched.getBody().path("source").asText()).isEqualTo("codex");
        assertThat(fetched.getBody().path("toolInputJson").asText()).isEqualTo(input.toString());
        assertThat(fetched.getBody().path("toolOutputJson").asText()).isEqualTo(output.toString());
        assertThat(fetched.getBody().path("metadata")).isEqualTo(metadata);
        if (idempotent) {
            var replay = http.postForEntity(endpoint, payload, JsonNode.class);
            assertThat(replay.getBody().path("replayed").asBoolean()).isTrue();
            assertThat(replay.getBody().path("eventId").asText()).isEqualTo(eventId);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void credentialAssignmentsInStringLeavesAreSanitizedBeforeStorageAndRead(boolean idempotent) throws Exception {
        String text = "before password=\"FAKE SPACE CREDENTIAL\" after\n"
                + "{\"api_key\":\"F4KE7\",\"note\":\"readable\"}\n"
                + "client_secret='FAKE \\'QUOTED\\' CREDENTIAL' finish";
        String expected = "before password=\"[REDACTED]\" after\n"
                + "{\"api_key\":\"[REDACTED]\",\"note\":\"readable\"}\n"
                + "client_secret='[REDACTED]' finish";
        Map<String, Object> event = Map.of(
                "source",
                "codex",
                "clientSessionId",
                "string-redaction-" + UUID.randomUUID(),
                "eventType",
                "PostToolUse",
                "cwd",
                "/fixture/redaction",
                "toolName",
                "fixture-tool",
                "text",
                text,
                "toolInput",
                Map.of("stdout", List.of(text)),
                "toolOutput",
                Map.of("stdout", text),
                "metadata",
                Map.of("fixture", text, "repo", "/fixture/redaction"));
        Object payload = idempotent ? Map.of("captureId", UUID.randomUUID().toString(), "event", event) : event;
        String endpoint = base + (idempotent ? "/api/events/idempotent" : "/api/events");
        var captured = http.postForEntity(endpoint, payload, JsonNode.class);
        assertThat(captured.getStatusCode().value()).isEqualTo(200);
        String eventId = captured.getBody().path("eventId").asText();
        assertThat(eventId).isNotBlank();
        Map<String, Object> persisted = jdbc.queryForMap(
                "SELECT text, tool_input_json, tool_output_json, metadata_json FROM agent_events WHERE id = ?",
                eventId);
        assertThat(persisted.values().toString()).doesNotContain("FAKE", "F4KE7");
        assertThat(persisted.get("text")).isEqualTo(expected);
        assertThat(mapper.readTree(persisted.get("tool_input_json").toString())
                        .path("stdout")
                        .get(0)
                        .asText())
                .isEqualTo(expected);
        assertThat(mapper.readTree(persisted.get("tool_output_json").toString())
                        .path("stdout")
                        .asText())
                .isEqualTo(expected);
        assertThat(mapper.readTree(persisted.get("metadata_json").toString())
                        .path("fixture")
                        .asText())
                .isEqualTo(expected);
        var fetched = http.getForEntity(base + "/api/events/" + eventId, JsonNode.class);
        assertThat(fetched.getStatusCode().value()).isEqualTo(200);
        assertThat(fetched.getBody().toString()).doesNotContain("FAKE", "F4KE7");
        assertThat(fetched.getBody().path("text").asText()).isEqualTo(expected);
        assertThat(fetched.getBody().path("metadata").path("repo").asText()).isEqualTo("/fixture/redaction");
        if (idempotent) {
            var replay = http.postForEntity(endpoint, payload, JsonNode.class);
            assertThat(replay.getBody().path("replayed").asBoolean()).isTrue();
            assertThat(replay.getBody().path("eventId").asText()).isEqualTo(eventId);
        }
    }
}
