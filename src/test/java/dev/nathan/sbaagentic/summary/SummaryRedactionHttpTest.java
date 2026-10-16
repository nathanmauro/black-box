package dev.nathan.sbaagentic.summary;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import dev.nathan.sbaagentic.SbaAgenticApplication;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * A session captured before a redaction rule existed can still hold a displayed credential. The
 * default summary backend is an external command, so nothing it is sent, and nothing it answers
 * that is then stored as the session summary or title, may carry that credential. A fake command
 * records each request; synthetic values only.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SummaryRedactionHttpTest {
    private static final String FAKE_KEY = "apikey_0123456789abcdef0123456789abcdef_fedcba9876543210fedcba9876543210";
    private static final String[] SECRETS = {"0123456789abcdef", "fedcba9876543210", "F4KE7", "correct horse battery"};
    private static final String LEGACY_TOOL_TEXT =
            "{\"stdout\":\"\\tenvironment = {\\n\\t\\tPATH => /usr/bin:/bin\\n\\t\\tSBA_DB_PASSWORD => F4KE7legacyDb"
                    + "\\n\\t\\tSBA_JUDGE_API_KEY => " + FAKE_KEY
                    + "\\n\\t\\tSBA_SIGNING_PASSPHRASE => F4KE7 correct horse battery\\n\\t\\tSBA_PORT => 8766\\n\\t}\\n"
                    + "<key>STRIPE_KEY</key>\\n<string>F4KE7legacyStripe</string>\",\"stderr\":\"\"}";
    private static final String CWD = "/fixture/summary-redaction";

    @TempDir
    static Path tempDir;

    private final TestRestTemplate http = new TestRestTemplate();
    private ServletWebServerApplicationContext app;
    private JdbcTemplate jdbc;
    private Path requests;
    private String base;

    @BeforeAll
    void start() throws Exception {
        requests = tempDir.resolve("summary-requests.txt");
        Path summarizer = tempDir.resolve("fake-summarizer.sh");
        // Records the request, then answers like a model that echoed a credential back.
        Files.writeString(summarizer, """
                { cat; printf '\\n-- end of request --\\n'; } >> '%s'
                printf '%%s\\n' '## SBA_JUDGE_API_KEY => %s was rotated' '' 'Set password: F4KE7modelEcho in the plist.'
                """.formatted(requests, FAKE_KEY));
        app = (ServletWebServerApplicationContext) new SpringApplicationBuilder(SbaAgenticApplication.class)
                .run(
                        "--spring.config.location=classpath:/application.yml",
                        "--spring.datasource.url=jdbc:sqlite:" + tempDir.resolve("summary-redaction.db"),
                        "--server.address=127.0.0.1",
                        "--server.port=0",
                        "--sba.auth.enabled=false",
                        "--sba.editor.enabled=false",
                        "--sba.local-ai.enabled=false",
                        "--sba.summary.backend=external",
                        "--sba.summary.external-command=/bin/sh " + summarizer,
                        "--sba.elasticsearch.enabled=false",
                        "--sba.memory.embedding.enabled=false",
                        "--sba.ask.embedding-enabled=false",
                        "--sba.judge.enabled=false",
                        "--spring.main.banner-mode=off",
                        "--logging.level.root=WARN");
        jdbc = app.getBean(JdbcTemplate.class);
        base = "http://127.0.0.1:" + app.getWebServer().getPort();
    }

    @AfterAll
    void stop() {
        if (app != null) app.close();
    }

    @Test
    void summaryBackendIsNeverSentAStoredCredentialAndItsAnswerIsStoredRedacted() throws Exception {
        String sessionId = legacySession();

        var summarized = http.postForEntity(base + "/api/sessions/" + sessionId + "/summarize", null, String.class);
        assertThat(summarized.getStatusCode().value()).isEqualTo(200);
        assertThat(summarized.getBody()).doesNotContain(SECRETS);

        String sent = Files.readString(requests);
        assertThat(sent)
                .contains("PATH => /usr/bin:/bin", "SBA_PORT => 8766", "SBA_DB_PASSWORD => [REDACTED]")
                .doesNotContain(SECRETS);
        Map<String, Object> stored =
                jdbc.queryForMap("SELECT summary, title FROM agent_sessions WHERE id = ?", sessionId);
        assertThat(stored.values().toString()).doesNotContain(SECRETS);
        assertThat(stored.get("title").toString()).startsWith("SBA_JUDGE_API_KEY => [REDACTED]");
        assertThat(stored.get("summary").toString()).contains("password: [REDACTED]");
    }

    @Test
    void directMeldPreviewNeverSendsAStoredCredential() throws Exception {
        String sessionId = legacySession();
        Files.deleteIfExists(requests);
        JsonNode projects = http.getForObject(base + "/api/projects", JsonNode.class);
        String projectKey = null;
        for (JsonNode project : projects) {
            if (CWD.equals(project.path("canonicalKey").asText())) {
                projectKey = project.path("projectKey").asText();
            }
        }
        assertThat(projectKey).isNotNull();

        var preview = http.postForEntity(
                base + "/api/projects/" + projectKey + "/melds/preview",
                Map.of("sessionIds", List.of(sessionId), "executionMode", "direct"),
                String.class);
        assertThat(preview.getStatusCode().value()).isEqualTo(200);

        assertThat(Files.readString(requests))
                .contains("Project Meld Bundle", "SBA_DB_PASSWORD => [REDACTED]")
                .doesNotContain(SECRETS);
    }

    /** A session whose tool event text was stored before any rule recognized its credentials. */
    private String legacySession() {
        String clientSessionId = "summary-redaction-" + UUID.randomUUID();
        capture(clientSessionId, "UserPromptSubmit", null, "Why does the Black Box service fail to start?");
        String toolEventId = capture(clientSessionId, "PostToolUse", "Bash", "placeholder tool output");
        jdbc.update("UPDATE agent_events SET text = ? WHERE id = ?", LEGACY_TOOL_TEXT, toolEventId);

        return jdbc.queryForObject(
                "SELECT id FROM agent_sessions WHERE client_session_id = ?", String.class, clientSessionId);
    }

    private String capture(String clientSessionId, String eventType, String toolName, String text) {
        Map<String, Object> event = new java.util.LinkedHashMap<>();
        event.put("source", "claude");
        event.put("clientSessionId", clientSessionId);
        event.put("eventType", eventType);
        event.put("cwd", CWD);
        event.put("toolName", toolName);
        event.put("text", text);
        var captured = http.postForEntity(base + "/api/events", event, JsonNode.class);
        assertThat(captured.getStatusCode().value()).isEqualTo(200);

        return captured.getBody().path("eventId").asText();
    }
}
