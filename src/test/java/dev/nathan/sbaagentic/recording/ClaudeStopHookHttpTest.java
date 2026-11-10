package dev.nathan.sbaagentic.recording;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.nathan.sbaagentic.SbaAgenticApplication;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
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

/** Actual Claude Stop hook -> HTTP -> SQLite -> search and transcript, with no JSONL or model. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ClaudeStopHookHttpTest {
    @TempDir
    static Path tempDir;

    private final TestRestTemplate http = new TestRestTemplate();
    private ServletWebServerApplicationContext app;
    private ObjectMapper mapper;
    private JdbcTemplate jdbc;
    private String base;

    @BeforeAll
    void start() throws Exception {
        tempDir = tempDir.toRealPath();
        app = (ServletWebServerApplicationContext) new SpringApplicationBuilder(SbaAgenticApplication.class)
                .run(
                        "--spring.config.location=classpath:/application.yml",
                        "--spring.datasource.url=jdbc:sqlite:" + tempDir.resolve("stop.db"),
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
        if (app != null) {
            app.close();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void finalAssistantTurnsAreSearchableAndVisibleWithoutJsonl(boolean durable) throws Exception {
        ObjectNode payload = (ObjectNode) mapper.readTree(
                Path.of("scripts/hooks/fixtures/claude-stop.json").toFile());
        String clientSessionId = "claude-stop-" + UUID.randomUUID();
        payload.put("session_id", clientSessionId);
        String finalMessage = payload.path("last_assistant_message").asText();

        // A continuing Stop hook is still a completed response, not an instruction to drop it.
        for (boolean continuing : List.of(false, true)) {
            String marker = "cedar" + UUID.randomUUID().toString().replace("-", "");
            String expectedText = marker + "\n" + finalMessage;
            payload.put("last_assistant_message", expectedText);
            payload.put("stop_hook_active", continuing);
            runHook(payload, durable);

            String query = "source:claude kind:Stop " + marker;
            JsonNode search = http.getForObject(base + "/api/search?q={query}", JsonNode.class, query);
            assertThat(search.path("local")).hasSize(1);
            JsonNode event = search.path("local").get(0);
            assertThat(event.path("source").asText()).isEqualTo("claude");
            assertThat(event.path("clientSessionId").asText()).isEqualTo(clientSessionId);
            assertThat(event.path("eventType").asText()).isEqualTo("Stop");
            assertThat(event.path("role").asText()).isEqualTo("assistant");
            assertThat(event.path("text").asText()).isEqualTo(expectedText);
            assertThat(event.path("metadata").path("transcript_path").asText())
                    .isEqualTo(payload.path("transcript_path").asText());
            assertThat(event.path("metadata").has("rawHook")).isEqualTo(!durable);
            assertThat(jdbc.queryForObject(
                            "SELECT text FROM agent_events WHERE id = ?",
                            String.class,
                            event.path("id").asText()))
                    .isEqualTo(expectedText);

            JsonNode compact = http.getForObject(base + "/api/search/compact?q={query}", JsonNode.class, query);
            assertThat(compact.path("items")).hasSize(1);
            assertThat(compact.path("items").get(0).path("eventId").asText())
                    .isEqualTo(event.path("id").asText());

            JsonNode transcript = http.getForObject(
                    base + "/api/sessions/{sessionId}/transcript",
                    JsonNode.class,
                    event.path("sessionId").asText());
            assertThat(transcript.path("available").asBoolean()).isFalse();
            assertThat(transcript.path("events")).hasSize(continuing ? 2 : 1);
            JsonNode projected = null;
            for (JsonNode candidate : transcript.path("events")) {
                if (candidate.path("id").equals(event.path("id"))) {
                    projected = candidate;
                }
            }
            assertThat(projected).isNotNull();
            assertThat(projected.path("text").asText()).isEqualTo(expectedText);
            assertThat(projected.path("role").asText()).isEqualTo("assistant");
            assertThat(projected.path("metadata").has("rawHook")).isFalse();
        }
    }

    private void runHook(JsonNode payload, boolean durable) throws Exception {
        Path input = tempDir.resolve("hook-input.json");
        Path output = tempDir.resolve("hook-output.txt");
        Path errors = tempDir.resolve("hook-errors.txt");
        Files.writeString(input, mapper.writeValueAsString(payload));
        ProcessBuilder builder = new ProcessBuilder(
                        "bash",
                        Path.of("scripts/hooks/sba-agent-hook.sh")
                                .toAbsolutePath()
                                .toString(),
                        "claude")
                .directory(tempDir.toFile())
                .redirectInput(input.toFile())
                .redirectOutput(output.toFile())
                .redirectError(errors.toFile());
        builder.environment().clear();
        builder.environment().put("PATH", System.getenv("PATH"));
        builder.environment().put("HOME", tempDir.toString());
        builder.environment().put("TMPDIR", tempDir.toString());
        builder.environment().put("SBA_AGENTIC_URL", base);
        builder.environment().put("SBA_CAPTURE_DURABLE", durable ? "1" : "0");
        builder.environment()
                .put("SBA_CAPTURE_OUTBOX_DIR", tempDir.resolve("outbox").toString());
        Process process = builder.start();
        try {
            assertThat(process.waitFor(10, TimeUnit.SECONDS))
                    .as("Claude Stop hook completes")
                    .isTrue();
            assertThat(process.exitValue()).as(Files.readString(errors)).isZero();
            assertThat(Files.readString(errors)).isEmpty();
            assertThat(Files.readString(output)).isEmpty();
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
            }
        }
    }
}
