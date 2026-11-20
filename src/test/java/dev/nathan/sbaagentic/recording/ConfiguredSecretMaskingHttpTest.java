package dev.nathan.sbaagentic.recording;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nathan.sbaagentic.SbaAgenticApplication;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The running service is started with credential-named settings, then an agent displays their values
 * in shapes no display rule recognizes. Through the real HTTP capture, summary and meld paths and the
 * canonical SQLite store, no stored, indexed, returned or outbound copy holds a value. Synthetic
 * values only, with no provider shape, on a temporary database.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConfiguredSecretMaskingHttpTest {
    private static final String JUDGE_KEY = "Zq7Lm2xVbT9pKc4WnR8sHd3F";
    private static final String AUTH_TOKEN = "Hy5Tg8RfD2sWq9Xz4Nb7Mv1K";
    private static final String[] SECRETS = {JUDGE_KEY, AUTH_TOKEN};
    private static final String CWD = "/fixture/configured-secret-masking";
    // None of these lines pairs a value with a credential name the display rules would act on.
    private static final String DISPLAY = """
        12c12
        <     <string>%1$s</string>
        ---
        >     <string>rotated</string>
        | setting | value | source |
        | judge | %1$s | launchd |
        "ProgramArguments" = ("/usr/bin/java"; "--bearer"; "%2$s";);
        the local model key is still lm-studio
        """.formatted(JUDGE_KEY, AUTH_TOKEN);

    @TempDir
    static Path tempDir;

    private final TestRestTemplate http = new TestRestTemplate();
    private final ObjectMapper json = new ObjectMapper();
    private ServletWebServerApplicationContext app;
    private JdbcTemplate jdbc;
    private Path requests;
    private String base;

    @BeforeAll
    void start() throws Exception {
        requests = tempDir.resolve("summary-requests.txt");
        Path summarizer = tempDir.resolve("fake-summarizer.sh");
        // Records the request, then answers like a model that restated the values in Markdown.
        Files.writeString(summarizer, """
            { cat; printf '\\n-- end of request --\\n'; } >> '%s'
            printf '%%s\\n' '## Judge moved to %s' '' '| judge | %s | launchd |' '- bearer argument: `%s`'
            """.formatted(requests, JUDGE_KEY, JUDGE_KEY, AUTH_TOKEN));
        app = run(
                "masking.db",
                "--sba.summary.backend=external",
                "--sba.summary.external-command=/bin/sh " + summarizer,
                "--sba.ingestion.redact-enabled=true");
        jdbc = app.getBean(JdbcTemplate.class);
        base = "http://127.0.0.1:" + app.getWebServer().getPort();
    }

    @AfterAll
    void stop() {
        if (app != null) app.close();
    }

    private ServletWebServerApplicationContext run(String database, String... extra) {
        List<String> args = new ArrayList<>(List.of(
                "--spring.config.location=classpath:/application.yml",
                "--spring.datasource.url=jdbc:sqlite:" + tempDir.resolve(database),
                "--server.address=127.0.0.1",
                "--server.port=0",
                "--sba.auth.enabled=false",
                "--sba.editor.enabled=false",
                "--sba.local-ai.enabled=false",
                "--sba.elasticsearch.enabled=false",
                "--sba.memory.embedding.enabled=false",
                "--sba.ask.embedding-enabled=false",
                "--sba.judge.enabled=false",
                // The service's own credentials, as a property and as an environment-style name.
                "--sba.judge.api-key=" + JUDGE_KEY,
                "--SBA_AUTH_API_TOKEN=" + AUTH_TOKEN,
                "--spring.main.banner-mode=off",
                "--logging.level.root=WARN"));
        args.addAll(List.of(extra));

        return (ServletWebServerApplicationContext)
                new SpringApplicationBuilder(SbaAgenticApplication.class).run(args.toArray(String[]::new));
    }

    @Test
    void capturedDisplayStoresIndexesAndReturnsNoConfiguredValue() throws Exception {
        String client = "masking-" + UUID.randomUUID();
        Map<String, Object> toolInput = Map.of("command", "diff old.plist new.plist; echo " + AUTH_TOKEN);
        Map<String, Object> toolResponse = Map.of("stdout", DISPLAY, "stderr", "");
        Map<String, Object> event = event(client, "PostToolUse", "Bash", json.writeValueAsString(toolResponse));
        event.put("toolInput", toolInput);
        event.put("toolOutput", toolResponse);
        event.put(
                "metadata",
                Map.of("rawHook", Map.of("tool_input", toolInput, "tool_response", toolResponse), JUDGE_KEY, "name"));
        String eventId = capture(base, event);

        Map<String, Object> stored = jdbc.queryForMap(
                "SELECT text, tool_input_json, tool_output_json, metadata_json, human_text FROM agent_events WHERE id"
                        + " = ?",
                eventId);
        assertThat(stored.values().toString()).doesNotContain(SECRETS);
        String stdout = json.readTree(stored.get("tool_output_json").toString())
                .path("stdout")
                .asText();
        // Only the values are gone: the evidence around them, and a weak default value, are kept.
        assertThat(stdout).isEqualTo(DISPLAY.replace(JUDGE_KEY, "[REDACTED]").replace(AUTH_TOKEN, "[REDACTED]"));
        assertThat(stdout).contains("| judge | [REDACTED] | launchd |", "still lm-studio");
        assertThat(json.readTree(stored.get("tool_input_json").toString())
                        .path("command")
                        .asText())
                .isEqualTo("diff old.plist new.plist; echo [REDACTED]");

        assertThat(ftsHits(eventId, "\"" + JUDGE_KEY + "\"")).isZero();
        assertThat(ftsHits(eventId, "\"" + AUTH_TOKEN + "\"")).isZero();
        assertThat(ftsHits(eventId, "\"launchd\"")).isOne();

        var fetched = http.getForEntity(base + "/api/events/" + eventId, String.class);
        assertThat(fetched.getStatusCode().value()).isEqualTo(200);
        assertThat(fetched.getBody()).doesNotContain(SECRETS).contains("launchd");
    }

    @Test
    void aPromptThatRestatesAValueNeverBecomesATitleOrHumanTurn() {
        String client = "masking-" + UUID.randomUUID();
        String prompt = "the judge is `" + JUDGE_KEY + "` now; why does launchd still pass the old one?";
        String eventId = capture(base, event(client, "UserPromptSubmit", null, prompt));

        Map<String, Object> stored = jdbc.queryForMap(
                "SELECT e.text, e.human_text, s.title, s.first_human_turn FROM agent_events e"
                        + " JOIN agent_sessions s ON s.id = e.session_id WHERE e.id = ?",
                eventId);

        assertThat(stored.values().toString()).doesNotContain(SECRETS);
        assertThat(stored.get("title").toString()).startsWith("the judge is `[REDACTED]` now;");

        var sessions = http.getForEntity(base + "/api/sessions", String.class);
        assertThat(sessions.getBody()).doesNotContain(SECRETS);
    }

    @Test
    void summaryBackendIsNeverSentAValueStoredEarlierAndItsAnswerIsStoredMasked() throws Exception {
        String sessionId = sessionStoredBeforeMasking();
        Files.deleteIfExists(requests);

        var summarized = http.postForEntity(base + "/api/sessions/" + sessionId + "/summarize", null, String.class);
        assertThat(summarized.getStatusCode().value()).isEqualTo(200);
        assertThat(summarized.getBody()).doesNotContain(SECRETS);

        assertThat(Files.readString(requests))
                .contains("| judge | [REDACTED] | launchd |", "still lm-studio")
                .doesNotContain(SECRETS);
        Map<String, Object> stored =
                jdbc.queryForMap("SELECT summary, title FROM agent_sessions WHERE id = ?", sessionId);
        assertThat(stored.values().toString()).doesNotContain(SECRETS);
        assertThat(stored.get("title").toString()).isEqualTo("Judge moved to [REDACTED]");
        assertThat(stored.get("summary").toString())
                .contains("| judge | [REDACTED] | launchd |", "bearer argument: `[REDACTED]`");
    }

    @Test
    void directMeldPreviewNeitherSendsNorReturnsAValue() throws Exception {
        String sessionId = sessionStoredBeforeMasking();
        Files.deleteIfExists(requests);

        var preview = http.postForEntity(
                base + "/api/projects/" + projectKey(CWD) + "/melds/preview",
                Map.of("sessionIds", List.of(sessionId), "executionMode", "direct"),
                String.class);
        assertThat(preview.getStatusCode().value()).isEqualTo(200);

        assertThat(Files.readString(requests))
                .contains("Project Meld Bundle", "| judge | [REDACTED] | launchd |")
                .doesNotContain(SECRETS);
        // The backend's answer restates the values, and is shown before any save redacts it. The
        // response's local bundle is a read of the stored rows, which masking does not rewrite.
        String answer = json.readTree(preview.getBody()).path("preview").asText();
        assertThat(answer)
                .contains("Judge moved to [REDACTED]", "| judge | [REDACTED] | launchd |")
                .doesNotContain(SECRETS);
    }

    @Test
    void disabledIngestionRedactionStillMasksConfiguredValuesOnly() {
        try (ServletWebServerApplicationContext unredacted =
                run("masking-disabled.db", "--sba.summary.backend=local", "--sba.ingestion.redact-enabled=false")) {
            String other = "http://127.0.0.1:" + unredacted.getWebServer().getPort();
            String text = "export OTHER_PASSWORD=F4KE7other; judge value " + JUDGE_KEY;
            String eventId = capture(other, event("masking-" + UUID.randomUUID(), "PostToolUse", "Bash", text));

            String stored = unredacted
                    .getBean(JdbcTemplate.class)
                    .queryForObject("SELECT text FROM agent_events WHERE id = ?", String.class, eventId);

            assertThat(stored).isEqualTo("export OTHER_PASSWORD=F4KE7other; judge value [REDACTED]");
        }
    }

    @Test
    void maskingSwitchedOffStoresTheTextAsCaptured() {
        try (ServletWebServerApplicationContext unmasked = run(
                "masking-off.db",
                "--sba.summary.backend=local",
                "--sba.ingestion.redact-enabled=true",
                "--sba.ingestion.mask-configured-secrets=false")) {
            String other = "http://127.0.0.1:" + unmasked.getWebServer().getPort();
            String text = "| judge | " + JUDGE_KEY + " | launchd |";
            String eventId = capture(other, event("masking-" + UUID.randomUUID(), "PostToolUse", "Bash", text));

            String stored = unmasked.getBean(JdbcTemplate.class)
                    .queryForObject("SELECT text FROM agent_events WHERE id = ?", String.class, eventId);

            assertThat(stored).isEqualTo(text);
        }
    }

    /** A session with a row written directly, as text stored before value masking existed. */
    private String sessionStoredBeforeMasking() throws Exception {
        String client = "masking-" + UUID.randomUUID();
        capture(base, event(client, "UserPromptSubmit", null, "Why did the judge stop answering?"));
        String toolEventId = capture(base, event(client, "PostToolUse", "Bash", "placeholder tool output"));
        jdbc.update(
                "UPDATE agent_events SET text = ? WHERE id = ?",
                json.writeValueAsString(Map.of("stdout", DISPLAY)),
                toolEventId);

        return jdbc.queryForObject("SELECT id FROM agent_sessions WHERE client_session_id = ?", String.class, client);
    }

    private String projectKey(String cwd) {
        JsonNode projects = http.getForObject(base + "/api/projects", JsonNode.class);
        for (JsonNode project : projects) {
            if (cwd.equals(project.path("canonicalKey").asText())) {

                return project.path("projectKey").asText();
            }
        }
        throw new AssertionError("no project for " + cwd);
    }

    private Map<String, Object> event(String clientSessionId, String eventType, String toolName, String text) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("source", "claude");
        event.put("clientSessionId", clientSessionId);
        event.put("eventType", eventType);
        event.put("cwd", CWD);
        event.put("toolName", toolName);
        event.put("text", text);

        return event;
    }

    private String capture(String baseUrl, Map<String, Object> event) {
        var captured = http.postForEntity(baseUrl + "/api/events", event, JsonNode.class);
        assertThat(captured.getStatusCode().value()).isEqualTo(200);
        String eventId = captured.getBody().path("eventId").asText();
        assertThat(eventId).isNotBlank();

        return eventId;
    }

    private int ftsHits(String eventId, String match) {

        return jdbc.queryForObject(
                "SELECT count(*) FROM event_fts JOIN agent_events e ON e.rowid = event_fts.rowid"
                        + " WHERE event_fts MATCH ? AND e.id = ?",
                Integer.class,
                match,
                eventId);
    }
}
