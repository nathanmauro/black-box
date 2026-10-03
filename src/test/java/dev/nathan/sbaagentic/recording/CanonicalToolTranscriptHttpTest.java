package dev.nathan.sbaagentic.recording;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nathan.sbaagentic.SbaAgenticApplication;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

/** Real durable sanitizer, HTTP receipt capture and SQLite transcript projection; no local JSONL. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CanonicalToolTranscriptHttpTest {
    @TempDir
    static Path tempDir;

    private final TestRestTemplate http = new TestRestTemplate();
    private ServletWebServerApplicationContext app;
    private ObjectMapper mapper;
    private JdbcTemplate jdbc;
    private String base;

    @BeforeAll
    void start() {
        app = (ServletWebServerApplicationContext) new SpringApplicationBuilder(SbaAgenticApplication.class)
                .run(
                        "--spring.config.location=classpath:/application.yml",
                        "--spring.datasource.url=jdbc:sqlite:" + tempDir.resolve("transcript.db"),
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
                        "--sba.transcript.claude-roots[0]=" + tempDir.resolve("unavailable"),
                        "--sba.transcript.codex-roots[0]=" + tempDir.resolve("unavailable"),
                        "--spring.main.banner-mode=off",
                        "--logging.level.root=WARN");
        mapper = app.getBean(ObjectMapper.class);
        jdbc = app.getBean(JdbcTemplate.class);
        base = "http://127.0.0.1:" + app.getWebServer().getPort();
        assertThat(app.getBean(TranscriptProperties.class).getClaudeRoots())
                .containsExactly(tempDir.resolve("unavailable").toString());
    }

    @AfterAll
    void stop() {
        if (app != null) app.close();
    }

    @Test
    void durableSanitizationAndLegacyCaptureProjectTheSameOutputWithoutRewritingEvents() throws Exception {
        String text = "fixture output\nwith a \"quoted\" line";
        for (boolean durable : List.of(true, false)) {
            JsonNode event = event(text, text);
            if (durable) {
                event = sanitize(event);
                assertThat(event.path("metadata").has("rawHook")).isFalse();
            }
            verifyProjectionAndReplay(event, null);
        }
    }

    @Test
    void distinctStatusAndTruncatedPrefixesRemainVisible() throws Exception {
        verifyProjectionAndReplay(sanitize(event("finished fixture", "fixture output")), "finished fixture");
        String large = "z".repeat(22_000);
        verifyProjectionAndReplay(sanitize(event(large, large)), "z".repeat(20_000) + "\n[truncated]");
    }

    private JsonNode event(String text, String output) {

        return mapper.valueToTree(Map.of(
                "source",
                "claude",
                "clientSessionId",
                "transcript-" + UUID.randomUUID(),
                "eventType",
                "PostToolUse",
                "role",
                "tool",
                "cwd",
                "/fixture/transcript",
                "toolName",
                "Bash",
                "text",
                text,
                "toolOutput",
                output,
                "metadata",
                Map.of("rawHook", Map.of("tool_response", output), "trace", "keep"),
                "observedAt",
                "2026-10-03T12:00:00.123456Z"));
    }

    private void verifyProjectionAndReplay(JsonNode event, String expectedText) {
        var envelope = Map.of("captureId", UUID.randomUUID().toString(), "event", event);
        var captured = http.postForEntity(base + "/api/events/idempotent", envelope, JsonNode.class);
        assertThat(captured.getStatusCode().value()).isEqualTo(200);
        String eventId = captured.getBody().path("eventId").asText();
        String sessionId = captured.getBody().path("sessionId").asText();
        Map<String, Object> before = jdbc.queryForMap("SELECT * FROM agent_events WHERE id = ?", eventId);
        JsonNode canonical = http.getForObject(base + "/api/events/" + eventId, JsonNode.class);
        JsonNode transcript = http.getForObject(base + "/api/sessions/" + sessionId + "/transcript", JsonNode.class);
        assertThat(transcript.path("available").asBoolean()).isFalse();
        assertThat(transcript.path("reason").asText()).isEqualTo("not-recorded");
        assertThat(transcript.path("events").size()).isEqualTo(1);
        JsonNode projected = transcript.path("events").get(0);
        assertThat(projected.path("id").asText()).isEqualTo(eventId);
        assertThat(projected.path("text").isNull()).isEqualTo(expectedText == null);
        if (expectedText != null) assertThat(projected.path("text").asText()).isEqualTo(expectedText);
        assertThat(projected.path("toolOutputJson")).isEqualTo(canonical.path("toolOutputJson"));
        assertThat(projected.path("observedAt").asText()).isEqualTo("2026-10-03T12:00:00.123456Z");
        assertThat(projected.path("metadata").has("rawHook")).isFalse();
        assertThat(projected.path("metadata").path("trace").asText()).isEqualTo("keep");
        var replay = http.postForEntity(base + "/api/events/idempotent", envelope, JsonNode.class);
        assertThat(replay.getBody().path("replayed").asBoolean()).isTrue();
        assertThat(replay.getBody().path("eventId").asText()).isEqualTo(eventId);
        assertThat(jdbc.queryForMap("SELECT * FROM agent_events WHERE id = ?", eventId))
                .isEqualTo(before);
        assertThat(http.getForObject(base + "/api/events/" + eventId, JsonNode.class))
                .isEqualTo(canonical);
    }

    private JsonNode sanitize(JsonNode event) throws Exception {
        Path source = Path.of("scripts/hooks/capture_outbox.py").toAbsolutePath();
        Path input = tempDir.resolve("input.json");
        Path output = tempDir.resolve("sanitized.json");
        Path errors = tempDir.resolve("sanitizer.err");
        Files.writeString(input, mapper.writeValueAsString(event));
        String script = "import importlib.util,json,sys; "
                + "spec=importlib.util.spec_from_file_location('fixture_outbox',sys.argv[1]); "
                + "module=importlib.util.module_from_spec(spec); spec.loader.exec_module(module); "
                + "sys.stdout.buffer.write(module.sanitize_event(json.load(sys.stdin)))";
        ProcessBuilder builder = new ProcessBuilder("python3", "-B", "-c", script, source.toString())
                .directory(tempDir.toFile())
                .redirectInput(input.toFile())
                .redirectOutput(output.toFile())
                .redirectError(errors.toFile());
        builder.environment().clear();
        builder.environment().put("PATH", System.getenv("PATH"));
        builder.environment().put("HOME", tempDir.toString());
        builder.environment().put("TMPDIR", tempDir.toString());
        Process process = builder.start();
        try {
            assertThat(process.waitFor(10, TimeUnit.SECONDS))
                    .as("fixture sanitizer completes")
                    .isTrue();
            assertThat(process.exitValue()).as(Files.readString(errors)).isZero();

            return mapper.readTree(Files.readString(output));
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
            }
        }
    }
}
