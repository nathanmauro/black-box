package dev.nathan.sbaagentic.summary;

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
 * A session captured before a redaction rule existed can still hold a displayed credential. The
 * default summary backend is an external command, so nothing it is sent, and nothing it answers
 * that is then stored as the session summary or title, may carry that credential. Text that holds
 * no credential reaches it as stored. A fake command records each request; synthetic values only.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SummaryRedactionHttpTest {
    private static final String FAKE_KEY = "apikey_0123456789abcdef0123456789abcdef_fedcba9876543210fedcba9876543210";
    private static final String[] SECRETS = {"0123456789abcdef", "fedcba9876543210", "F4KE7", "correct horse battery"};
    // A launchd display stored before its rules existed, as the capture hook stores a Bash result.
    private static final String LEGACY_DISPLAY = """
            \tenvironment = {
            \t\tPATH => /usr/bin:/bin
            \t\tSBA_DB_PASSWORD => F4KE7legacyDb
            \t\tSBA_JUDGE_API_KEY => %s
            \t\tSBA_SIGNING_PASSPHRASE => F4KE7 correct horse battery
            \t\tSBA_X_PASSWORD => F4KE7qa"F4KE7quotedTail rest
            \t\tJevApiKey => F4KE7mixedCase
            \t\tSBA_SIGNING_KEY => $F4KE7dollar
            \t\tSBA_PORT => 8766
            \t}
            24:\t\tSBA_Y_PASSWORD => F4KE7gutter F4KE7gutterTail
              "SBA_Z_PASSWORD" => "F4KE7q1"F4KE7inner F4KE7innerTail"
            <key>STRIPE_KEY</key>
            <string>F4KE7legacyStripe</string>
            Hapikey_0123456789abcdef0123456789abcdef_fedcba9876543210fedcba9876543210PID""".formatted(FAKE_KEY);
    private static final String CWD = "/fixture/summary-redaction";
    // Tool inputs that hold no credential but whose strings end with a name and separator.
    private static final Map<String, Object> TOKEN_QUEUE_EDIT = object(
            "file_path",
            "/fixture/app/queue.py",
            "old_string",
            "class TokenQueue:",
            "new_string",
            "class TokenQueue(Queue):",
            "replace_all",
            false);
    private static final Map<String, Object> TOKEN_GREP =
            object("command", "grep -rn \"token:\" config/app.yml", "description", "Find token keys");

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
        // Records the request, then answers like a model that echoed credentials back.
        Files.writeString(summarizer, """
                { cat; printf '\\n-- end of request --\\n'; } >> '%s'
                printf '%%s\\n' '## JevApiKey => F4KE7sumTitle was rotated' '' 'Set password: F4KE7modelEcho in the plist.' \\
                  '- STRIPE_KEY => $F4KE7sumDollar' '- the judge now uses %sready'
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
        Files.deleteIfExists(requests);

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
        assertThat(stored.get("title").toString()).startsWith("JevApiKey => [REDACTED]");
        assertThat(stored.get("summary").toString())
                .contains("password: [REDACTED]", "STRIPE_KEY => [REDACTED]", "uses [REDACTED]ready");
    }

    @Test
    void directMeldPreviewNeverSendsAStoredCredential() throws Exception {
        String sessionId = legacySession();
        Files.deleteIfExists(requests);

        var preview = http.postForEntity(
                base + "/api/projects/" + projectKey(CWD) + "/melds/preview",
                Map.of("sessionIds", List.of(sessionId), "executionMode", "direct"),
                String.class);
        assertThat(preview.getStatusCode().value()).isEqualTo(200);

        assertThat(Files.readString(requests))
                .contains("Project Meld Bundle", "SBA_DB_PASSWORD => [REDACTED]")
                .doesNotContain(SECRETS);
    }

    /**
     * A session whose stored text holds names that only resemble credentials (token counts, package
     * names), and tool inputs whose credential ingestion already replaced, reaches the summary backend
     * as stored: no event loses its text, and a stored tool input stays valid JSON.
     */
    @Test
    void summaryBackendReceivesTheStoredTextOfAnOrdinarySessionUnchanged() throws Exception {
        String cwd = "/fixture/summary-ordinary";
        String client = "summary-ordinary-" + UUID.randomUUID();
        capture(client, cwd, "UserPromptSubmit", null, "Why is the local judge slow?", null);
        Map<String, Object> read = Map.of(
                "type",
                "text",
                "file",
                Map.of(
                        "filePath",
                        "/fixture/judge.json",
                        "content",
                        "{\n  \"model\": \"qwen2.5:7b\",\n  \"max_tokens\": 4096,\n  \"temperature\": 0.2,\n"
                                + "  \"max_input_chars\": 8000\n}",
                        "numLines",
                        6));
        capture(client, cwd, "PostToolUse", "Read", json.writeValueAsString(read), read);
        capture(
                client,
                cwd,
                "PostToolUse",
                "Bash",
                "{\"stdout\":\"{\\\"usage\\\":{\\\"input_tokens\\\":12,\\\"output_tokens\\\":34},"
                        + "\\\"benchmark\\\":\\\"SUMMARY-C-BENCH 41 ms\\\"}\",\"stderr\":\"\"}",
                null);
        capture(
                client,
                cwd,
                "PostToolUse",
                "Read",
                "\"@csstools/css-tokenizer\": \"^3.0.4\",\n\"js-tokens\": \"^4.0.0\",\n\"after\": \"SUMMARY-C-AFTER\"",
                null);
        // A PreToolUse event without text: the backend is sent its stored tool input, whose last
        // value ingestion replaced, so the replacement sits before the quote that closes the string.
        for (String command : List.of(
                "defaults write com.apple.screensaver askForPassword -int F4KE7lock",
                "node server.js --api-key F4KE7flag",
                "export SBA_DB_PASSWORD=F4KE7env",
                "mysql --user=root password=F4KE7pw")) {
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("command", command);
            input.put("description", "SUMMARY-C-INPUT");
            captureInput(client, cwd, "PreToolUse", "Bash", input);
        }
        // Tool inputs that hold no credential: strings that end with a name and separator, a quoted
        // name after which ingestion replaced the rest of its string, and a file longer than the scan
        // budget, which ingestion clipped. Each reaches the backend as stored, as valid JSON.
        captureInput(client, cwd, "PreToolUse", "Grep", object("pattern", "password:", "path", "config"));
        captureInput(client, cwd, "PreToolUse", "Grep", object("pattern", "api_key=", "glob", "*.env"));
        captureInput(client, cwd, "PreToolUse", "Grep", object("pattern", "token =", "path", "src"));
        captureInput(client, cwd, "PreToolUse", "Edit", TOKEN_QUEUE_EDIT);
        captureInput(client, cwd, "PreToolUse", "Bash", TOKEN_GREP);
        captureInput(
                client,
                cwd,
                "PreToolUse",
                "Write",
                object(
                        "file_path",
                        "/fixture/docs/notes.md",
                        "content",
                        "- Note: the release checklist covers build, smoke test and rollback steps.\n".repeat(800)));
        String sessionId = sessionId(client);
        Files.deleteIfExists(requests);

        var summarized = http.postForEntity(base + "/api/sessions/" + sessionId + "/summarize", null, String.class);
        assertThat(summarized.getStatusCode().value()).isEqualTo(200);

        String sent = Files.readString(requests);
        List<String> storedTexts = jdbc.queryForList(
                "SELECT text FROM agent_events WHERE session_id = ? AND text IS NOT NULL", String.class, sessionId);
        assertThat(storedTexts).hasSize(4);
        for (String stored : storedTexts) {
            assertThat(sent).as("the summary request holds the stored text").contains(stored);
        }
        assertThat(sent).contains("\\\"max_tokens\\\": 4096", "SUMMARY-C-BENCH 41 ms", "SUMMARY-C-AFTER");
        List<String> storedInputs = jdbc.queryForList(
                "SELECT tool_input_json FROM agent_events WHERE session_id = ? AND event_type = 'PreToolUse'",
                String.class,
                sessionId);
        assertThat(storedInputs).hasSize(10);
        assertThat(storedInputs.stream().filter(stored -> stored.contains("SUMMARY-C-INPUT")))
                .hasSize(4)
                .allSatisfy(
                        stored -> assertThat(stored).doesNotContain("F4KE7").contains("[REDACTED]\",\"description\""));
        assertThat(storedInputs).anySatisfy(stored -> assertThat(stored).hasSizeGreaterThan(50_000));
        for (String stored : storedInputs) {
            assertThat(json.readTree(stored).size()).isGreaterThan(1);
            assertThat(sent)
                    .as("the summary request holds the stored tool input")
                    .contains(stored);
        }
        assertThat(sent).doesNotContain("F4KE7");
    }

    /**
     * The summary backend reads each stored tool input up to the scan budget, as it reads any stored
     * text: the strings that start within the first 50,000 characters are sent, and one marker stands
     * in for the rest.
     */
    @Test
    void summaryBackendReadsEachStoredToolInputUpToTheScanBudget() throws Exception {
        String cwd = "/fixture/summary-budget";
        String client = "summary-budget-" + UUID.randomUUID();
        capture(client, cwd, "UserPromptSubmit", null, "Summarize the long build logs", null);
        List<String> parts = new ArrayList<>();
        for (int index = 1; index <= 13; index++) {
            parts.add("BUDGET-PART-%02d ".formatted(index) + "ordinary build output line\n".repeat(1_800));
        }
        captureInput(client, cwd, "PreToolUse", "Task", object("description", "Read the logs", "parts", parts));
        String sessionId = sessionId(client);
        Files.deleteIfExists(requests);

        var summarized = http.postForEntity(base + "/api/sessions/" + sessionId + "/summarize", null, String.class);
        assertThat(summarized.getStatusCode().value()).isEqualTo(200);

        assertThat(Files.readString(requests))
                .contains("BUDGET-PART-01", "BUDGET-PART-02", "…[truncated]")
                .doesNotContain("BUDGET-PART-03", "BUDGET-PART-13");
    }

    /**
     * Token counts and member names that only resemble credentials in one session never cost the
     * model the rest of the bundle: every selected session and its evidence are sent.
     */
    @Test
    void directMeldPreviewSendsTheEvidenceOfEverySelectedSession() throws Exception {
        String cwd = "/fixture/meld-evidence";
        String first = "meld-evidence-a-" + UUID.randomUUID();
        capture(
                first,
                cwd,
                "UserPromptSubmit",
                null,
                "Tune {\"model\": \"local\", \"max_tokens\": 4096} for the judge",
                null);
        Map<String, Object> task = new LinkedHashMap<>();
        task.put("totalDurationMs", 4100);
        task.put("totalTokens", 5120);
        task.put("usage", Map.of("input_tokens", 12, "output_tokens", 34));
        task.put("content", "SESSION-A-TASK-RESULT the judge prompt was tuned");
        capture(first, cwd, "PostToolUse", "Task", json.writeValueAsString(task), task);
        String second = "meld-evidence-b-" + UUID.randomUUID();
        capture(second, cwd, "UserPromptSubmit", null, "Ship the judge change", null);
        capture(second, cwd, "PostToolUse", "Bash", "SESSION-B-MARKER mvn test passed", null);
        assertThat(jdbc.queryForObject(
                        "SELECT tool_output_json FROM agent_events WHERE tool_name = 'Task' AND session_id = ?",
                        String.class,
                        sessionId(first)))
                .as("ingestion replaces token-count members whole")
                .contains("\"totalTokens\":\"[REDACTED]\"");
        Files.deleteIfExists(requests);

        var preview = http.postForEntity(
                base + "/api/projects/" + projectKey(cwd) + "/melds/preview",
                Map.of("sessionIds", List.of(sessionId(first), sessionId(second)), "executionMode", "direct"),
                String.class);
        assertThat(preview.getStatusCode().value()).isEqualTo(200);

        assertThat(Files.readString(requests))
                .contains("\"max_tokens\": 4096", "SESSION-A-TASK-RESULT", "## Session 2", "SESSION-B-MARKER");
    }

    /**
     * A direct meld preview sends the bundle it returns locally when the session holds no credential:
     * stored tool inputs and outputs whose strings end with a name and separator stay as stored.
     */
    @Test
    void directMeldPreviewSendsStoredToolJsonAsStored() throws Exception {
        String cwd = "/fixture/meld-tool-json";
        String client = "meld-tool-json-" + UUID.randomUUID();
        capture(client, cwd, "UserPromptSubmit", null, "Where are the token keys configured?", null);
        captureInput(client, cwd, "PreToolUse", "Grep", object("pattern", "password:", "path", "config"));
        captureInput(client, cwd, "PreToolUse", "Grep", object("pattern", "token =", "path", "src"));
        captureInput(client, cwd, "PreToolUse", "Edit", TOKEN_QUEUE_EDIT);
        captureInput(client, cwd, "PreToolUse", "Bash", TOKEN_GREP);
        capture(
                client,
                cwd,
                "PostToolUse",
                "Bash",
                null,
                object(
                        "stdout",
                        "config/app.yml:3:  token:\nconfig/app.yml:4:  password:",
                        "stderr",
                        "",
                        "interrupted",
                        false));
        Files.deleteIfExists(requests);

        var preview = http.postForEntity(
                base + "/api/projects/" + projectKey(cwd) + "/melds/preview",
                Map.of("sessionIds", List.of(sessionId(client)), "executionMode", "direct"),
                JsonNode.class);
        assertThat(preview.getStatusCode().value()).isEqualTo(200);

        String bundle = preview.getBody().path("bundle").asText();
        assertThat(bundle).contains("Input: {\"pattern\":\"password:\",\"path\":\"config\"}", "Output: {\"stdout\"");
        assertThat(Files.readString(requests))
                .as("the backend receives the bundle built from stored text")
                .contains(bundle);
    }

    /**
     * A direct meld preview reads each stored tool input and output only as far as the bundle shows
     * it, and what it shows is the stored text: a stored output far longer than that still sends the
     * bundle the preview returns locally.
     */
    @Test
    void directMeldPreviewReadsALongStoredOutputOnlyAsFarAsTheBundleShowsIt() throws Exception {
        String cwd = "/fixture/meld-long-output";
        String client = "meld-long-output-" + UUID.randomUUID();
        capture(client, cwd, "UserPromptSubmit", null, "Summarize the long build logs", null);
        List<String> parts = new ArrayList<>();
        for (int index = 1; index <= 13; index++) {
            parts.add("MELD-PART-%02d ".formatted(index) + "ordinary build output line\n".repeat(1_800));
        }
        capture(
                client,
                cwd,
                "PostToolUse",
                "Bash",
                null,
                object("stdout", parts.get(0), "parts", parts.subList(1, parts.size()), "stderr", ""));
        Files.deleteIfExists(requests);

        var preview = http.postForEntity(
                base + "/api/projects/" + projectKey(cwd) + "/melds/preview",
                Map.of("sessionIds", List.of(sessionId(client)), "executionMode", "direct"),
                JsonNode.class);
        assertThat(preview.getStatusCode().value()).isEqualTo(200);

        String bundle = preview.getBody().path("bundle").asText();
        assertThat(bundle).contains("Output: {\"stdout\":\"MELD-PART-01 ordinary", " [clipped]");
        assertThat(Files.readString(requests))
                .as("the backend receives the bundle built from stored text")
                .contains(bundle)
                .doesNotContain("MELD-PART-02");
    }

    private static Map<String, Object> object(Object... namesAndValues) {
        Map<String, Object> object = new LinkedHashMap<>();
        for (int index = 0; index < namesAndValues.length; index += 2) {
            object.put((String) namesAndValues[index], namesAndValues[index + 1]);
        }

        return object;
    }

    /** A session whose tool event text was stored before any rule recognized its credentials. */
    private String legacySession() throws Exception {
        String clientSessionId = "summary-redaction-" + UUID.randomUUID();
        capture(clientSessionId, CWD, "UserPromptSubmit", null, "Why does the Black Box service fail to start?", null);
        String toolEventId = capture(clientSessionId, CWD, "PostToolUse", "Bash", "placeholder tool output", null);
        String legacyText = json.writeValueAsString(Map.of("stdout", LEGACY_DISPLAY));
        jdbc.update("UPDATE agent_events SET text = ? WHERE id = ?", legacyText, toolEventId);

        return sessionId(clientSessionId);
    }

    private String sessionId(String clientSessionId) {

        return jdbc.queryForObject(
                "SELECT id FROM agent_sessions WHERE client_session_id = ?", String.class, clientSessionId);
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

    private void captureInput(
            String clientSessionId, String cwd, String eventType, String toolName, Map<String, Object> toolInput) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("source", "claude");
        event.put("clientSessionId", clientSessionId);
        event.put("eventType", eventType);
        event.put("cwd", cwd);
        event.put("toolName", toolName);
        event.put("toolInput", toolInput);
        var captured = http.postForEntity(base + "/api/events", event, JsonNode.class);
        assertThat(captured.getStatusCode().value()).isEqualTo(200);
    }

    private String capture(
            String clientSessionId, String cwd, String eventType, String toolName, String text, Object toolOutput) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("source", "claude");
        event.put("clientSessionId", clientSessionId);
        event.put("eventType", eventType);
        event.put("cwd", cwd);
        event.put("toolName", toolName);
        event.put("text", text);
        event.put("toolOutput", toolOutput);
        var captured = http.postForEntity(base + "/api/events", event, JsonNode.class);
        assertThat(captured.getStatusCode().value()).isEqualTo(200);

        return captured.getBody().path("eventId").asText();
    }
}
