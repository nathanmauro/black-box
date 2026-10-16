package dev.nathan.sbaagentic.recording;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.nathan.sbaagentic.SbaAgenticApplication;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * An agent displays a launchd service's configuration: the capture keeps that evidence, while the
 * stored event, its full-text index, its reads, its session and its Elasticsearch document never
 * hold the credential. Fake values only, through the real HTTP ingest and canonical SQLite store.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConfigSecretRedactionHttpTest {
    private static final String FAKE_KEY = "apikey_0123456789abcdef0123456789abcdef_fedcba9876543210fedcba9876543210";
    // The full-text tokenizer splits on '_', so each hex segment is its own indexed term.
    private static final String FAKE_SEGMENT = "0123456789abcdef0123456789abcdef";
    private static final String FAKE_SEGMENT_2 = "fedcba9876543210fedcba9876543210";
    private static final String PLIST = """
            <?xml version="1.0" encoding="UTF-8"?>
            <plist version="1.0">
            <dict>
              <key>Label</key>
              <string>com.example.blackbox</string>
              <key>EnvironmentVariables</key>
              <dict>
                <key>SBA_PORT</key>
                <string>8766</string>
                <key>SBA_JUDGE_API_KEY</key>
                <string>%s</string>
              </dict>
            </dict>
            </plist>
            """.formatted(FAKE_KEY);
    private static final String PLUTIL = """
            {
              "EnvironmentVariables" => {
                "SBA_JUDGE_API_KEY" => "%s"
                "SBA_PORT" => "8766"
              }
              "Label" => "com.example.blackbox"
            }
            """.formatted(FAKE_KEY);
    private static final String LAUNCHCTL =
            "\tenvironment = {\n\t\tSBA_JUDGE_API_KEY => " + FAKE_KEY + "\n\t}\n\tcontext_tokens: 1234\n";
    private static final String COMMAND = "plutil -p ~/Library/LaunchAgents/com.example.blackbox.plist; "
            + "/usr/bin/env SBA_X_TOKEN=F4KE7_ENV_VALUE /usr/bin/java -jar black-box.jar";
    // Synthetic values that each index as one full-text token.
    private static final List<String> HOOK_SECRET_TOKENS = List.of(
            FAKE_SEGMENT,
            FAKE_SEGMENT_2,
            "F4KE7dbPassword",
            "F4KE7stripeKey",
            "F4KE7oldDb",
            "F4KE7newDb",
            "F4KE7promptPass");
    private static final String[] HOOK_SECRETS = {FAKE_SEGMENT, FAKE_SEGMENT_2, "F4KE7", "correct horse battery"};
    private static final String LAUNCHCTL_PRINT = """
            gui/501/com.example.blackbox = {
            \tactive count = 1
            \tpath = /fixture/Library/LaunchAgents/com.example.blackbox.plist
            \tstate = running

            \tprogram = /usr/bin/java
            \targuments = {
            \t\t/usr/bin/java
            \t\t-jar
            \t\t/fixture/black-box.jar
            \t}

            \tenvironment = {
            \t\tPATH => /usr/bin:/bin
            \t\tSBA_DB_PASSWORD => F4KE7dbPassword
            \t\tSBA_JUDGE_API_KEY => %s
            \t\tSBA_SIGNING_PASSPHRASE => F4KE7 correct horse battery
            \t\tSTRIPE_KEY => F4KE7stripeKey
            \t\tSBA_PORT => 8766
            \t}
            }
            """.formatted(FAKE_KEY);

    @TempDir
    static Path tempDir;

    private final TestRestTemplate http = new TestRestTemplate();
    private final Map<String, String> elasticDocuments = new ConcurrentHashMap<>();
    private HttpServer elastic;
    private ServletWebServerApplicationContext app;
    private JdbcTemplate jdbc;
    private ObjectMapper mapper;
    private String base;

    @BeforeAll
    void start() throws IOException {
        elastic = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        elastic.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), UTF_8);
            String path = exchange.getRequestURI().getPath();
            if (path.contains("/_doc/")) {
                elasticDocuments.put(path.substring(path.lastIndexOf('/') + 1), body);
            }
            if ("HEAD".equals(exchange.getRequestMethod())) {
                // A missing index makes the client create it before its first document.
                exchange.sendResponseHeaders(404, -1);
                exchange.close();

                return;
            }
            byte[] ok = "{\"acknowledged\":true,\"result\":\"created\"}".getBytes(UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, ok.length);
            exchange.getResponseBody().write(ok);
            exchange.close();
        });
        elastic.start();
        app = (ServletWebServerApplicationContext) new SpringApplicationBuilder(SbaAgenticApplication.class)
                .run(
                        "--spring.config.location=classpath:/application.yml",
                        "--spring.datasource.url=jdbc:sqlite:" + tempDir.resolve("config-redaction.db"),
                        "--server.address=127.0.0.1",
                        "--server.port=0",
                        "--sba.auth.enabled=false",
                        "--sba.editor.enabled=false",
                        "--sba.local-ai.enabled=false",
                        "--sba.summary.backend=local",
                        "--sba.elasticsearch.enabled=true",
                        "--sba.elasticsearch.base-url=http://127.0.0.1:"
                                + elastic.getAddress().getPort(),
                        "--sba.elasticsearch.index-name=config-redaction",
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
        if (elastic != null) elastic.stop(0);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void displayedServiceConfigurationKeepsEvidenceButNoStoredOrProjectedCopyOfItsCredential(boolean idempotent)
            throws Exception {
        String stdout = PLIST + PLUTIL + LAUNCHCTL;
        Map<String, Object> toolInput = Map.of("command", COMMAND);
        Map<String, Object> toolResponse = Map.of("stdout", stdout, "stderr", "", "interrupted", false);
        // Like the capture hook: an object tool response is stored as JSON text, escaping its quotes and newlines.
        Map<String, Object> event = event(
                "PostToolUse",
                mapper.writeValueAsString(toolResponse),
                toolInput,
                toolResponse,
                Map.of(
                        "rawHook",
                        Map.of(
                                "hook_event_name",
                                "PostToolUse",
                                "tool_name",
                                "Bash",
                                "tool_input",
                                toolInput,
                                "tool_response",
                                toolResponse),
                        "repo",
                        "/fixture/config-redaction"));
        String eventId = capture(event, idempotent);

        Map<String, Object> stored = jdbc.queryForMap(
                "SELECT text, tool_input_json, tool_output_json, metadata_json, human_text FROM agent_events WHERE id = ?",
                eventId);
        assertThat(stored.values().toString()).doesNotContain(FAKE_SEGMENT, FAKE_SEGMENT_2, "F4KE7");
        assertThat(stored.get("text").toString())
                .contains("<string>com.example.blackbox</string>", "<string>8766</string>", "context_tokens: 1234")
                .contains("<key>SBA_JUDGE_API_KEY</key>\\n    <string>[REDACTED]</string>")
                .contains("\\\"SBA_JUDGE_API_KEY\\\" => \\\"[REDACTED]\\\"");
        String redactedStdout = mapper.readTree(stored.get("tool_output_json").toString())
                .path("stdout")
                .asText();
        assertThat(redactedStdout)
                .contains("<key>SBA_JUDGE_API_KEY</key>\n    <string>[REDACTED]</string>")
                .contains("\"SBA_JUDGE_API_KEY\" => \"[REDACTED]\"", "\"SBA_PORT\" => \"8766\"")
                .contains("SBA_JUDGE_API_KEY => [REDACTED]\n", "context_tokens: 1234");
        assertThat(mapper.readTree(stored.get("tool_input_json").toString())
                        .path("command")
                        .asText())
                .contains("/usr/bin/env SBA_X_TOKEN=[REDACTED] /usr/bin/java");
        JsonNode rawHook =
                mapper.readTree(stored.get("metadata_json").toString()).path("rawHook");
        assertThat(rawHook.path("tool_response").path("stdout").asText()).isEqualTo(redactedStdout);

        assertThat(ftsHits(eventId, "\"" + FAKE_SEGMENT + "\"")).isZero();
        assertThat(ftsHits(eventId, "\"" + FAKE_SEGMENT_2 + "\"")).isZero();
        assertThat(ftsHits(eventId, "\"blackbox\"")).isOne();

        var fetched = http.getForEntity(base + "/api/events/" + eventId, JsonNode.class);
        assertThat(fetched.getStatusCode().value()).isEqualTo(200);
        assertThat(fetched.getBody().toString())
                .doesNotContain(FAKE_SEGMENT, FAKE_SEGMENT_2, "F4KE7")
                .contains("com.example.blackbox");
        assertThat(elasticDocuments.get(eventId))
                .isNotNull()
                .doesNotContain(FAKE_SEGMENT, FAKE_SEGMENT_2)
                .contains("com.example.blackbox");
    }

    @Test
    void configurationPastedIntoAPromptNeverBecomesAHumanTurnOrSessionTitle() throws Exception {
        String prompt = "\"SBA_JUDGE_API_KEY\" => \"" + FAKE_KEY
                + "\" is what plutil shows; why is launchd ignoring it?\n" + PLIST;
        String eventId = capture(
                event("UserPromptSubmit", prompt, null, null, Map.of("repo", "/fixture/config-redaction")), false);

        Map<String, Object> stored = jdbc.queryForMap(
                "SELECT e.text, e.human_text, s.title, s.first_human_turn FROM agent_events e"
                        + " JOIN agent_sessions s ON s.id = e.session_id WHERE e.id = ?",
                eventId);
        assertThat(stored.values().toString()).doesNotContain(FAKE_SEGMENT, FAKE_SEGMENT_2);
        assertThat(stored.get("title"))
                .isEqualTo("\"SBA_JUDGE_API_KEY\" => \"[REDACTED]\" is what plutil shows; why is launchd ignoring it?");
        assertThat(stored.get("human_text").toString()).contains("<string>[REDACTED]</string>");
        assertThat(stored.get("first_human_turn")).isEqualTo(stored.get("human_text"));
        assertThat(ftsHits(eventId, "\"" + FAKE_SEGMENT + "\"")).isZero();
        assertThat(elasticDocuments.get(eventId))
                .isNotNull()
                .doesNotContain(FAKE_SEGMENT, FAKE_SEGMENT_2)
                .contains("is what plutil shows");
    }

    /**
     * The real capture hook (bash, jq and curl) posts Claude Code payloads as a live session would.
     * Its text is jq {@code tojson} of the tool result, so the displayed configuration arrives
     * JSON-escaped, and the whole hook payload rides along in {@code metadata.rawHook}.
     */
    @Test
    void claudeCodeHookCapturesOfServiceConfigurationStoreNoCredential() throws Exception {
        String session = "hook-config-redaction-" + UUID.randomUUID();
        String plist = "/fixture/Library/LaunchAgents/com.example.blackbox.plist";
        Map<String, String> eventIds = new LinkedHashMap<>();
        eventIds.put(
                "UserPromptSubmit",
                runHook(
                        session,
                        null,
                        Map.of(
                                "hook_event_name",
                                "UserPromptSubmit",
                                "prompt",
                                "SBA_DB_PASSWORD => F4KE7promptPass is in the plist, so why does launchd fail?\n"
                                        + LAUNCHCTL_PRINT)));
        eventIds.put(
                "Bash",
                runHook(
                        session,
                        "Bash",
                        Map.of(
                                "hook_event_name",
                                "PostToolUse",
                                "tool_name",
                                "Bash",
                                "tool_input",
                                Map.of(
                                        "command",
                                        "launchctl print gui/501/com.example.blackbox",
                                        "description",
                                        "Show the service environment"),
                                "tool_response",
                                Map.of("stdout", LAUNCHCTL_PRINT, "stderr", "", "interrupted", false, "isImage", false),
                                "tool_use_id",
                                "toolu_fixture_launchctl")));
        eventIds.put(
                "Grep",
                runHook(
                        session,
                        "Grep",
                        Map.of(
                                "hook_event_name",
                                "PostToolUse",
                                "tool_name",
                                "Grep",
                                "tool_input",
                                Map.of(
                                        "pattern",
                                        "SBA_JUDGE",
                                        "path",
                                        "/fixture/Library/LaunchAgents",
                                        "output_mode",
                                        "content",
                                        "-n",
                                        true,
                                        "-A",
                                        1),
                                "tool_response",
                                Map.of(
                                        "mode",
                                        "content",
                                        "numFiles",
                                        0,
                                        "filenames",
                                        List.of(),
                                        "content",
                                        plist + ":14:    <key>SBA_JUDGE_API_KEY</key>\n" + plist + "-15-    <string>"
                                                + FAKE_KEY + "</string>",
                                        "numLines",
                                        2),
                                "tool_use_id",
                                "toolu_fixture_grep")));
        String oldEntry = "    <key>SBA_DB_PASSWORD</key>\n    <string>F4KE7oldDb</string>";
        String newEntry = "    <key>SBA_DB_PASSWORD</key>\n    <string>F4KE7newDb</string>";
        eventIds.put(
                "Edit",
                runHook(
                        session,
                        "Edit",
                        Map.of(
                                "hook_event_name",
                                "PostToolUse",
                                "tool_name",
                                "Edit",
                                "tool_input",
                                Map.of("file_path", plist, "old_string", oldEntry, "new_string", newEntry),
                                "tool_response",
                                Map.of(
                                        "filePath",
                                        plist,
                                        "oldString",
                                        oldEntry,
                                        "newString",
                                        newEntry,
                                        "originalFile",
                                        PLIST.replace(
                                                "    <key>SBA_PORT</key>", oldEntry + "\n    <key>SBA_PORT</key>"),
                                        "structuredPatch",
                                        List.of(Map.of(
                                                "oldStart",
                                                11,
                                                "oldLines",
                                                4,
                                                "newStart",
                                                11,
                                                "newLines",
                                                4,
                                                "lines",
                                                List.of(
                                                        "     <key>SBA_DB_PASSWORD</key>",
                                                        "-    <string>F4KE7oldDb</string>",
                                                        "+    <string>F4KE7newDb</string>",
                                                        "     <key>SBA_PORT</key>",
                                                        "     <string>8766</string>"))),
                                        "userModified",
                                        false,
                                        "replaceAll",
                                        false),
                                "tool_use_id",
                                "toolu_fixture_edit")));

        for (Map.Entry<String, String> captured : eventIds.entrySet()) {
            String eventId = captured.getValue();
            Map<String, Object> stored = jdbc.queryForMap(
                    "SELECT text, tool_input_json, tool_output_json, metadata_json, human_text FROM agent_events"
                            + " WHERE id = ?",
                    eventId);
            assertThat(stored.values().toString()).as(captured.getKey()).doesNotContain(HOOK_SECRETS);
            assertThat(stored.get("metadata_json").toString())
                    .as(captured.getKey())
                    .contains("rawHook");
            for (String token : HOOK_SECRET_TOKENS) {
                assertThat(ftsHits(eventId, "\"" + token + "\""))
                        .as("%s full-text token %s", captured.getKey(), token)
                        .isZero();
            }
            assertThat(http.getForObject(base + "/api/events/" + eventId, String.class))
                    .as(captured.getKey())
                    .doesNotContain(HOOK_SECRETS);
            assertThat(elasticDocuments.get(eventId))
                    .as(captured.getKey())
                    .isNotNull()
                    .doesNotContain(HOOK_SECRETS);
        }
        assertThat(jdbc.queryForObject(
                        "SELECT text FROM agent_events WHERE id = ?", String.class, eventIds.get("Bash")))
                .contains("PATH => /usr/bin:/bin", "SBA_PORT => 8766", "/fixture/black-box.jar")
                .contains("SBA_DB_PASSWORD => [REDACTED]\\n", "STRIPE_KEY => [REDACTED]\\n");
        assertThat(ftsHits(eventIds.get("Bash"), "\"blackbox\"")).isOne();
        Map<String, Object> sessionRow = jdbc.queryForMap(
                "SELECT title, first_human_turn, summary FROM agent_sessions WHERE client_session_id = ?", session);
        assertThat(sessionRow.values().toString()).doesNotContain(HOOK_SECRETS);
        assertThat(sessionRow.get("title").toString()).startsWith("SBA_DB_PASSWORD => [REDACTED]");
    }

    // Each synthetic value below indexes as one full-text token, and none may be stored anywhere.
    private static final List<String> LAUNCHD_SECRETS = List.of(
            "F4KE7quoteA",
            "F4KE7quoteTailA",
            "F4KE7quoteB",
            "F4KE7quoteTailB",
            "F4KE7mixedName",
            "F4KE7lowerName",
            "F4KE7dottedName",
            "F4KE7dollar",
            "F4KE7firstLine",
            "F4KE7secondLine",
            "F4KE7gutter",
            "F4KE7gutterTail",
            "F4KE7plutilInner",
            "F4KE7plutilTail",
            "F4KE7argPlutil",
            "F4KE7argLaunchctl",
            "F4KE7replace",
            "F4KE7setenv",
            "F4KE7buddySet",
            "F4KE7buddyPrint",
            "F4KE7buddyPrintTail",
            "F4KE7buddyArg",
            "F4KE7promptTok",
            "F4KE7stopMixed",
            "F4KE7springSecret",
            "F4KE7springHikari",
            "F4KE7springPlutil",
            "F4KE7springEnv",
            "F4KE7springJson",
            "F4KE7springFlag",
            FAKE_SEGMENT,
            FAKE_SEGMENT_2);
    private static final String LAUNCHCTL_QUOTES_AND_NAMES = """
            gui/501/com.example.blackbox = {
            \tprogram = /usr/bin/java
            \targuments = {
            \t\t/usr/bin/java
            \t\t--api-key
            \t\tF4KE7argLaunchctl
            \t\t--port
            \t}

            \tenvironment = {
            \t\tPATH => /usr/bin:/bin
            \t\tSBA_DB_PASSWORD => F4KE7quoteA"F4KE7quoteTailA rest
            \t\tSBA_JUDGE_API_KEY => F4KE7quoteB\\"F4KE7quoteTailB
            \t\tJevApiKey => F4KE7mixedName
            \t\tdb_password => F4KE7lowerName two words
            \t\tsba.judge.password => F4KE7dottedName
            \t\tSBA_SIGNING_KEY => $F4KE7dollar
            \t\tSBA_X_TOKEN => F4KE7firstLine
            F4KE7secondLine
            \t\tSBA_PORT => 8766
            \t}
            }
            """;

    /**
     * The launchd displays and writes that stored credentials in review, through the real capture hook
     * in its default and durable modes: quotes and lowercase names in launchctl print, gutters,
     * plutil -p, binary property-list bytes, write commands, PlistBuddy, a pasted prompt and a Stop
     * message. Each lands in its own session, so the session title is checked too.
     */
    @ParameterizedTest(name = "durable={0}")
    @ValueSource(booleans = {false, true})
    void claudeCodeHookCapturesOfLaunchdDisplaysAndWritesStoreNoCredential(boolean durable) throws Exception {
        String prefix = "hook-launchd-" + (durable ? "durable-" : "") + UUID.randomUUID();
        String plist = "/fixture/Library/LaunchAgents/com.example.blackbox.plist";
        Map<String, Map<String, Object>> payloads = new LinkedHashMap<>();
        payloads.put(
                "launchctl print", bash("launchctl print gui/501/com.example.blackbox", LAUNCHCTL_QUOTES_AND_NAMES));
        payloads.put(
                "launchctl print | grep -n",
                bash(
                        "launchctl print gui/501/com.example.blackbox | grep -n PASSWORD",
                        "24:\t\tSBA_DB_PASSWORD => F4KE7gutter F4KE7gutterTail\n"));
        payloads.put("plutil -p", bash("plutil -p " + plist, """
                        {
                          "EnvironmentVariables" => {
                            "SBA_DB_PASSWORD" => "F4KE7plutilInner"F4KE7plutilTail rest"
                            "SBA_PORT" => "8766"
                          }
                          "ProgramArguments" => [
                            0 => "/usr/bin/java"
                            1 => "--api-key"
                            2 => "F4KE7argPlutil"
                          ]
                        }
                        """));
        payloads.put(
                "strings and cat of a binary plist",
                bash(
                        "strings " + plist + "; cat " + plist,
                        "EnvironmentVariables\nSBA_JUDGE_API_KEY\nH" + FAKE_KEY + "\nbplist00Ô\u0001\u0002_\u0010H"
                                + FAKE_KEY + "\u0008\n" + FAKE_KEY + "PID\tStatus\tLabel\n"));
        payloads.put(
                "commands that write the credential",
                bash(
                        "plutil -replace EnvironmentVariables.SBA_DB_PASSWORD -string 'F4KE7replace' " + plist
                                + " && launchctl setenv SBA_DB_PASSWORD F4KE7setenv"
                                + " && /usr/libexec/PlistBuddy -c \"Set :EnvironmentVariables:SBA_JUDGE_API_KEY"
                                + " F4KE7buddySet\" " + plist + " && /usr/libexec/PlistBuddy -c Print " + plist,
                        """
                        Dict {
                            EnvironmentVariables = Dict {
                                SBA_DB_PASSWORD = F4KE7buddyPrint F4KE7buddyPrintTail
                                SBA_PORT = 8766
                            }
                            ProgramArguments = Array {
                                /usr/bin/java
                                --db-password
                                F4KE7buddyArg
                            }
                        }
                        """));
        payloads.put(
                "pasted prompt",
                Map.of(
                        "hook_event_name",
                        "UserPromptSubmit",
                        "prompt",
                        "judgeToken => F4KE7promptTok is set, so why does launchd still fail?"));
        payloads.put(
                "Stop message",
                Map.of(
                        "hook_event_name",
                        "Stop",
                        "stop_hook_active",
                        false,
                        "last_assistant_message",
                        "JevApiKey => F4KE7stopMixed was rotated; launchctl print now shows it."));
        // Names with more than forty characters before their credential word.
        payloads.put(
                "long credential names",
                bash(
                        "launchctl print gui/501/com.example.blackbox; java -jar black-box.jar"
                                + " --spring-datasource-hikari-data-source-properties-password F4KE7springFlag",
                        """
                        \tenvironment = {
                        \t\tSPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GITHUB_CLIENTSECRET => F4KE7springSecret
                        \t\tSPRING_DATASOURCE_HIKARI_DATA_SOURCE_PROPERTIES_PASSWORD => F4KE7springHikari tail
                        \t}
                          "SPRING_DATASOURCE_HIKARI_DATA_SOURCE_PROPERTIES_PASSWORD" => "F4KE7springPlutil"
                        {"SPRING_DATASOURCE_HIKARI_DATA_SOURCE_PROPERTIES_PASSWORD": "F4KE7springJson"}
                        SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GITHUB_CLIENTSECRET=F4KE7springEnv
                        exit status 0
                        """));

        int index = 0;
        for (Map.Entry<String, Map<String, Object>> payload : payloads.entrySet()) {
            String session = prefix + "-" + index++;
            Map<String, Object> fields = payload.getValue();
            String eventId = runHook(session, (String) fields.get("tool_name"), fields, durable);
            String what = payload.getKey() + (durable ? " (durable)" : "");
            Map<String, Object> stored = jdbc.queryForMap(
                    "SELECT text, tool_input_json, tool_output_json, metadata_json, human_text FROM agent_events"
                            + " WHERE id = ?",
                    eventId);
            assertThat(stored.values().toString()).as(what).doesNotContain(LAUNCHD_SECRETS);
            for (String token : LAUNCHD_SECRETS) {
                assertThat(ftsHits(eventId, "\"" + token + "\""))
                        .as("%s full-text token %s", what, token)
                        .isZero();
            }
            assertThat(http.getForObject(base + "/api/events/" + eventId, String.class))
                    .as(what)
                    .doesNotContain(LAUNCHD_SECRETS);
            assertThat(elasticDocuments.get(eventId)).as(what).isNotNull().doesNotContain(LAUNCHD_SECRETS);
            Map<String, Object> sessionRow = jdbc.queryForMap(
                    "SELECT title, first_human_turn, summary FROM agent_sessions WHERE client_session_id = ?", session);
            assertThat(sessionRow.values().toString()).as(what).doesNotContain(LAUNCHD_SECRETS);
        }
        String launchctl = jdbc.queryForObject(
                "SELECT e.text FROM agent_events e JOIN agent_sessions s ON s.id = e.session_id"
                        + " WHERE s.client_session_id = ?",
                String.class,
                prefix + "-0");
        assertThat(launchctl)
                .contains("PATH => /usr/bin:/bin", "SBA_PORT => 8766", "--port")
                .contains("SBA_DB_PASSWORD => [REDACTED]\\n", "JevApiKey => [REDACTED]\\n");
        assertThat(jdbc.queryForObject(
                        "SELECT title FROM agent_sessions WHERE client_session_id = ?", String.class, prefix + "-5"))
                .startsWith("judgeToken => [REDACTED]");
        assertThat(jdbc.queryForObject(
                        "SELECT e.text FROM agent_events e JOIN agent_sessions s ON s.id = e.session_id"
                                + " WHERE s.client_session_id = ?",
                        String.class,
                        prefix + "-7"))
                .contains("SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GITHUB_CLIENTSECRET => [REDACTED]\\n")
                .contains("SPRING_DATASOURCE_HIKARI_DATA_SOURCE_PROPERTIES_PASSWORD => [REDACTED]\\n");
    }

    private static Map<String, Object> bash(String command, String stdout) {

        return Map.of(
                "hook_event_name",
                "PostToolUse",
                "tool_name",
                "Bash",
                "tool_input",
                Map.of("command", command, "description", "Inspect the service configuration"),
                "tool_response",
                Map.of("stdout", stdout, "stderr", "", "interrupted", false, "isImage", false),
                "tool_use_id",
                "toolu_fixture_" + Integer.toHexString(command.hashCode()));
    }

    /** Runs scripts/hooks/sba-agent-hook.sh on one payload and returns the event it recorded. */
    private String runHook(String session, String toolName, Map<String, Object> fields) throws Exception {

        return runHook(session, toolName, fields, false);
    }

    /**
     * Runs scripts/hooks/sba-agent-hook.sh on one payload, directly or through its durable outbox in
     * a private temporary queue, and returns the event it recorded.
     */
    private String runHook(String session, String toolName, Map<String, Object> fields, boolean durable)
            throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("session_id", session);
        payload.put("transcript_path", "/fixture/.claude/projects/fixture/" + session + ".jsonl");
        payload.put("cwd", "/fixture/config-redaction");
        payload.put("permission_mode", "default");
        payload.putAll(fields);
        Path input = tempDir.resolve("hook-" + UUID.randomUUID() + ".json");
        Path errors = tempDir.resolve("hook-" + UUID.randomUUID() + ".err");
        Files.writeString(input, mapper.writeValueAsString(payload));
        ProcessBuilder builder = new ProcessBuilder(
                        "bash",
                        Path.of("scripts/hooks/sba-agent-hook.sh")
                                .toAbsolutePath()
                                .toString(),
                        "claude")
                .directory(tempDir.toFile())
                .redirectInput(input.toFile())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(errors.toFile());
        builder.environment().clear();
        builder.environment().put("PATH", System.getenv("PATH"));
        builder.environment().put("HOME", tempDir.toString());
        builder.environment().put("TMPDIR", tempDir.toString());
        builder.environment().put("SBA_CAPTURE_DURABLE", durable ? "1" : "0");
        builder.environment().put("SBA_AGENTIC_URL", base);
        if (durable) {
            // The outbox refuses a queue path through a symbolic link, such as macOS's /var.
            builder.environment()
                    .put(
                            "SBA_CAPTURE_OUTBOX_DIR",
                            tempDir.toRealPath()
                                    .resolve("outbox-" + UUID.randomUUID())
                                    .toString());
        }
        Process process = builder.start();
        try {
            assertThat(process.waitFor(20, TimeUnit.SECONDS))
                    .as("hook completes")
                    .isTrue();
            assertThat(process.exitValue()).as(Files.readString(errors)).isZero();
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
        List<String> ids = jdbc.queryForList(
                "SELECT e.id FROM agent_events e JOIN agent_sessions s ON s.id = e.session_id"
                        + " WHERE s.client_session_id = ? AND e.event_type = ? AND coalesce(e.tool_name, '') = ?",
                String.class,
                session,
                fields.get("hook_event_name"),
                toolName == null ? "" : toolName);
        assertThat(ids)
                .as(
                        "the hook recorded one %s event (jq, curl and python3 are required): %s",
                        fields.get("hook_event_name"), Files.readString(errors))
                .hasSize(1);

        return ids.get(0);
    }

    private Map<String, Object> event(
            String eventType, String text, Object toolInput, Object toolOutput, Map<String, Object> metadata) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("source", "claude");
        event.put("clientSessionId", "config-redaction-" + UUID.randomUUID());
        event.put("eventType", eventType);
        event.put("cwd", "/fixture/config-redaction");
        event.put("toolName", toolInput == null ? null : "Bash");
        event.put("text", text);
        event.put("toolInput", toolInput);
        event.put("toolOutput", toolOutput);
        event.put("metadata", metadata);

        return event;
    }

    private String capture(Map<String, Object> event, boolean idempotent) {
        Object payload = idempotent ? Map.of("captureId", UUID.randomUUID().toString(), "event", event) : event;
        String endpoint = base + (idempotent ? "/api/events/idempotent" : "/api/events");
        var captured = http.postForEntity(endpoint, payload, JsonNode.class);
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
