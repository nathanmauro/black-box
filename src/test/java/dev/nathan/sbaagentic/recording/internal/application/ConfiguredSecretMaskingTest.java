package dev.nathan.sbaagentic.recording.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nathan.sbaagentic.recording.IngestionProperties;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * The service's own configured credential values never survive a redaction entry point, whatever
 * shape displays them. Synthetic values only; none has a provider shape the built-in rules know.
 */
class ConfiguredSecretMaskingTest {
    private static final String SECRET = "Zq7Lm2xVbT9pKc4WnR8sHd3F";
    // Every character an encoding rewrites: quote, backslash, slash, markup, space, plus, percent.
    private static final String AWKWARD = "p@s\"s\\W0/rd+<&>'x y%z";
    private static final String MARK = "[REDACTED]";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ConfiguredSecretMasker masker = ConfiguredSecretMasker.ofValues(List.of(SECRET, AWKWARD));
    private final RedactionService redactor = new RedactionService(new IngestionProperties(), masker);

    /** The shapes NAT-388 lists as open, plus the ones the display rules already cover. */
    static Stream<Arguments> displayShapes() {

        return Stream.of(
                Arguments.of("bare prose", "the service answered with " + SECRET + " and exited"),
                Arguments.of("plain diff of a plist", """
                    12,13c12,13
                    <     <key>SBA_JUDGE_SETTING</key>
                    <     <string>%s</string>
                    ---
                    >     <key>SBA_JUDGE_SETTING</key>
                    >     <string>rotated</string>
                    """.formatted(SECRET)),
                Arguments.of("unified diff", "-    <string>" + SECRET + "</string>\n+    <string>rotated</string>"),
                Arguments.of("plutil quoted dotted key", "\"sba.judge.setting\" => \"" + SECRET + "\""),
                Arguments.of("JSON quoted dotted key", "{\"sba.judge.setting\": \"" + SECRET + "\"}"),
                Arguments.of("defaults read", "\"sync.auth.setting\" = \"" + SECRET + "\";"),
                Arguments.of(
                        "launchctl list ProgramArguments",
                        "\"ProgramArguments\" = (\n\t\"/usr/bin/java\";\n\t\"--setting\";\n\t\"" + SECRET + "\";\n);"),
                Arguments.of(
                        "defaults write dict-add",
                        "defaults write com.example EnvironmentVariables -dict-add SBA_SETTING " + SECRET),
                Arguments.of("markdown code restatement", "`SBA_SETTING` => `" + SECRET + "`"),
                Arguments.of("markdown bold restatement", "**SBA_SETTING**: " + SECRET),
                Arguments.of("markdown table row", "| SBA_SETTING | " + SECRET + " | launchd |"),
                Arguments.of("grep path:line:col prefix", "config/app.plist:14:13:    <string>" + SECRET + "</string>"),
                Arguments.of("grep path with spaces", "My Library/app.plist:14:<string>" + SECRET + "</string>"),
                Arguments.of("less -N numbering", "     14 <string>" + SECRET + "</string>"),
                Arguments.of("cat -A line ends", "SBA_SETTING=" + SECRET + "$"),
                Arguments.of("ANSI colour", "\u001b[32mSBA_SETTING\u001b[0m=\u001b[33m" + SECRET + "\u001b[0m"),
                Arguments.of("space-separated flag", "java -jar app.jar --signing-setting " + SECRET + " --port 8080"),
                Arguments.of("lowercase name", "jev_setting = " + SECRET),
                Arguments.of("camelCase name", "JevSetting: " + SECRET),
                Arguments.of("Python subscript write", "os.environ[\"X_SETTING\"] = \"" + SECRET + "\""),
                Arguments.of("Java property write", "System.setProperty(\"sba.judge.setting\", \"" + SECRET + "\")"),
                Arguments.of("nested container", "{\"setting\": {\"current\": [\"" + SECRET + "\"]}}"),
                Arguments.of("environment assignment", "export SBA_SETTING=" + SECRET),
                Arguments.of("env listing", "PATH=/usr/bin:/bin\nSBA_SETTING=" + SECRET + "\nHOME=/fixture"),
                Arguments.of("YAML block sequence", "args:\n  - --setting\n  - " + SECRET),
                Arguments.of("URL query", "curl https://example.invalid/hook?key=" + SECRET + "&v=1"),
                Arguments.of("Maven settings", "<server><id>repo</id><passphrase>" + SECRET + "</passphrase></server>"),
                Arguments.of("glued to text", "prefix" + SECRET + "suffix"),
                Arguments.of("line continuation", "java -jar app.jar --setting \\\n  " + SECRET));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("displayShapes")
    void everyEntryPointMasksTheValueInAnyDisplayShape(String shape, String display) throws Exception {
        assertThat(redactor.redact(display)).contains(MARK).doesNotContain(SECRET);
        assertThat(redactor.redactForExport(display)).contains(MARK).doesNotContain(SECRET);
        assertThat(redactor.redactForSummary(display)).contains(MARK).doesNotContain(SECRET);

        String storedJson = JSON.writeValueAsString(Map.of("stdout", display, "stderr", ""));
        assertThat(redactor.redactJsonForSummary(storedJson)).contains(MARK).doesNotContain(SECRET);
        // Stored JSON carried as text, as the capture hook sends an object tool response.
        assertThat(redactor.redact(storedJson)).contains(MARK).doesNotContain(SECRET);
        assertThat(redactor.redact(JSON.writeValueAsString(storedJson))).doesNotContain(SECRET);

        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("tool_response", Map.of("stdout", display, "lines", List.of("first", display)));
        assertThat(redactor.redactDeep(tree).toString()).contains(MARK).doesNotContain(SECRET);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("displayShapes")
    void shapesTheDisplayRulesDoNotKnowKeepEverythingAroundTheValue(String shape, String display) {
        // The masker alone changes only the value, so the evidence around it is kept exactly.
        assertThat(masker.mask(display)).isEqualTo(display.replace(SECRET, MARK));
    }

    @Test
    void summaryTextThatHoldsNoRuleShapeLosesOnlyTheValue() {
        String text = "| SBA_SETTING | " + SECRET + " | launchd |";

        assertThat(redactor.redactForSummary(text)).isEqualTo("| SBA_SETTING | [REDACTED] | launchd |");
        assertThat(redactor.redact(text)).isEqualTo("| SBA_SETTING | [REDACTED] | launchd |");
    }

    static Stream<Arguments> encodings() throws Exception {
        String json = JSON.writeValueAsString(AWKWARD);
        String jsonBody = json.substring(1, json.length() - 1);
        String jsonInJson = JSON.writeValueAsString(json);
        String htmlSafe = "p@s\\\"s\\\\W0/rd+\\u003c\\u0026\\u003e\\u0027x y%z";

        return Stream.of(
                Arguments.of("as written", AWKWARD),
                Arguments.of("JSON string", "{\"setting\": \"" + jsonBody + "\"}"),
                Arguments.of("JSON with escaped slashes", "{\"setting\": \"" + jsonBody.replace("/", "\\/") + "\"}"),
                Arguments.of("JSON inside a JSON string", "{\"stdout\": " + jsonInJson + "}"),
                Arguments.of("HTML-safe JSON", "{\"setting\": \"" + htmlSafe + "\"}"),
                Arguments.of("HTML-safe JSON, upper-case hex", "\"" + htmlSafe.replace("\\u003c", "\\u003C").replace("\\u003e", "\\u003E") + "\""),
                Arguments.of("form URL encoding", "setting=" + URLEncoder.encode(AWKWARD, StandardCharsets.UTF_8)),
                Arguments.of(
                        "percent URL encoding",
                        "https://example.invalid/?setting="
                                + URLEncoder.encode(AWKWARD, StandardCharsets.UTF_8)
                                        .replace("+", "%20")),
                Arguments.of(
                        "lower-case percent encoding",
                        "setting="
                                + URLEncoder.encode(AWKWARD, StandardCharsets.UTF_8)
                                        .replace("%22", "%22")
                                        .replace("%2F", "%2f")
                                        .replace("%5C", "%5c")
                                        .replace("%3C", "%3c")
                                        .replace("%3E", "%3e")
                                        .replace("%2B", "%2b")),
                Arguments.of("plist XML", "<string>p@s\"s\\W0/rd+&lt;&amp;&gt;'x y%z</string>"),
                Arguments.of("attribute XML", "<add value=\"p@s&quot;s\\W0/rd+&lt;&amp;&gt;&apos;x y%z\"/>"),
                Arguments.of(
                        "plist data",
                        "<data>"
                                + Base64.getEncoder().encodeToString(AWKWARD.getBytes(StandardCharsets.UTF_8))
                                + "</data>"),
                Arguments.of(
                        "Base64 URL alphabet",
                        "value: "
                                + Base64.getUrlEncoder()
                                        .withoutPadding()
                                        .encodeToString(AWKWARD.getBytes(StandardCharsets.UTF_8))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("encodings")
    void encodedFormsOfAValueAreMasked(String encoding, String display) {
        String base64 = Base64.getEncoder().withoutPadding().encodeToString(AWKWARD.getBytes(StandardCharsets.UTF_8));

        for (String result : List.of(
                masker.mask(display),
                redactor.redact(display),
                redactor.redactForExport(display),
                redactor.redactForSummary(display))) {
            assertThat(result)
                    .contains(MARK)
                    .doesNotContain("W0/rd", "W0\\/rd", "W0%2Frd", "W0%2frd", base64.substring(0, 16));
        }
    }

    @Test
    void disabledIngestionRedactionStillMasksOwnValuesAndNothingElse() {
        IngestionProperties disabled = new IngestionProperties();
        disabled.setRedactEnabled(false);
        RedactionService service = new RedactionService(disabled, masker);
        String text = "export OTHER_PASSWORD=F4KE7other; the judge key is " + SECRET;

        assertThat(service.redact(text)).isEqualTo("export OTHER_PASSWORD=F4KE7other; the judge key is [REDACTED]");
        assertThat(service.redactDeep(Map.of("password", "F4KE7other", SECRET, List.of(SECRET, 7))))
                .isEqualTo(Map.of("password", "F4KE7other", MARK, List.of(MARK, 7)));
        // The export boundary is mandatory either way.
        assertThat(service.redactForExport(text)).doesNotContain(SECRET, "F4KE7other");
    }

    @Test
    void disabledIngestionRedactionWithoutConfiguredValuesReturnsItsInput() {
        IngestionProperties disabled = new IngestionProperties();
        disabled.setRedactEnabled(false);
        RedactionService service = new RedactionService(disabled);
        Map<String, Object> tree = Map.of("password", "F4KE7other");
        String text = "the judge key is " + SECRET;

        assertThat(service.redact(text)).isSameAs(text);
        assertThat(service.redactDeep(tree)).isSameAs(tree);
    }

    @Test
    void customPatternsReplaceTheRulesButNotTheMasking() {
        IngestionProperties custom = new IngestionProperties();
        custom.setRedactPatterns(List.of("FAKE_CUSTOM_[0-9]+"));
        RedactionService service = new RedactionService(custom, masker);

        assertThat(service.redact("FAKE_CUSTOM_42 password=kept " + SECRET))
                .isEqualTo("[REDACTED] password=kept [REDACTED]");
        assertThat(service.redactDeep(Map.of("note", List.of(SECRET)))).isEqualTo(Map.of("note", List.of(MARK)));
    }

    @Test
    void structuredNamesAreMaskedAndStayDistinct() {
        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put(SECRET, "first");
        tree.put(MARK, "second");

        @SuppressWarnings("unchecked")
        Map<String, Object> redacted = (Map<String, Object>) redactor.redactDeep(tree);

        assertThat(redacted).hasSize(2).containsEntry(MARK, "second");
        assertThat(redacted.toString()).doesNotContain(SECRET).contains("first");
    }

    @Test
    void aValueAcrossTheScanBoundaryLeavesNoPrefixBehind() {
        // The scan budget is 50,000 characters: the value starts inside it and ends beyond it.
        String text = "x".repeat(50_000 - 10) + SECRET + " tail";

        for (String result :
                List.of(redactor.redact(text), redactor.redactForExport(text), redactor.redactForSummary(text))) {
            assertThat(result).doesNotContain(SECRET.substring(0, 6)).endsWith("…[truncated]");
        }
    }

    @Test
    void theLongerOfTwoOverlappingValuesWins() {
        ConfiguredSecretMasker overlapping =
                ConfiguredSecretMasker.ofValues(List.of("Ab3dEf7hIj", "Ab3dEf7hIjKl9nOp", "f7hIjKl9nOpQr5"));

        assertThat(overlapping.mask("key Ab3dEf7hIjKl9nOp end")).isEqualTo("key [REDACTED] end");
        assertThat(overlapping.mask("key Ab3dEf7hIjKl9nOpQr5 end")).isEqualTo("key [REDACTED]Qr5 end");
        assertThat(overlapping.mask("Ab3dEf7hIj Ab3dEf7hIj")).isEqualTo("[REDACTED] [REDACTED]");
    }

    @Test
    void textWithoutAConfiguredValueIsReturnedAsIs() {
        String text = "postgres is running; password: none set; Zq7Lm2xVbT9pKc4WnR8sHd3 is one short";

        assertThat(masker.mask(text)).isSameAs(text);
        assertThat(masker.mask(null)).isNull();
        assertThat(masker.mask("")).isEmpty();
        assertThat(ConfiguredSecretMasker.none().mask(SECRET)).isSameAs(SECRET);
        // Exact and case-sensitive: another letter case is another value.
        assertThat(masker.mask(SECRET.toLowerCase())).doesNotContain(MARK);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "postgres",
                "POSTGRES",
                "password",
                "changeme",
                "lm-studio",
                "sk-no-key-required",
                "short1A",
                "aaaaaaaaaaaa",
                "abababababab",
                "1234567890123456",
                "2026-11-19",
                "lowercase",
                "${SBA_JUDGE_API_KEY}",
                "${SBA_LOCAL_AI_API_KEY:lm-studio}",
                "https://api.example.invalid/v1/tokens",
                "jdbc://db.example.invalid/blackbox",
                "   ",
                ""
            })
    void weakValuesAreNeverRegistered(String weak) {
        ConfiguredSecretMasker weakOnly = ConfiguredSecretMasker.ofValues(List.of(weak));
        String text = "the database is " + weak + " and the docs say " + weak;

        assertThat(weakOnly.isEmpty()).isTrue();
        assertThat(weakOnly.mask(text)).isSameAs(text);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "Zq7Lm2xVbT9pKc4WnR8sHd3F",
                "hunter2-prod",
                "correcthorsebatterystaple",
                "/Ab3dEf7hIjKl9nOpQr5tUv",
                "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6",
                "p@s\"s\\W0/rd+<&>'x y%z"
            })
    void strongValuesAreRegistered(String strong) {

        assertThat(ConfiguredSecretMasker.ofValues(List.of(strong)).mask("x " + strong + " y"))
                .isEqualTo("x [REDACTED] y");
    }

    @Test
    void aSettingThatNamesWhereACredentialIsDoesNotMaskThatPath(@TempDir Path directory) throws Exception {
        Path tokenFile = Files.writeString(directory.resolve("api-token"), SECRET);
        Path notYetWritten = directory.resolve("rotated-token");
        ConfiguredSecretMasker paths =
                ConfiguredSecretMasker.ofValues(List.of(tokenFile.toString(), notYetWritten.toString()));

        assertThat(paths.isEmpty()).isTrue();
        assertThat(paths.mask("cat " + tokenFile)).isEqualTo("cat " + tokenFile);
    }

    @Test
    void aConnectionUrlRegistersItsPasswordOnly() {
        ConfiguredSecretMasker url = ConfiguredSecretMasker.ofValues(
                List.of("postgresql://blackbox:Xk29-vQp7_Lm4@db.example.invalid:5432/bb"));

        assertThat(url.secretCount()).isOne();
        assertThat(url.mask("psql postgresql://blackbox:Xk29-vQp7_Lm4@db.example.invalid:5432/bb"))
                .isEqualTo("psql postgresql://blackbox:[REDACTED]@db.example.invalid:5432/bb");
        assertThat(url.mask("PGPASSWORD=Xk29-vQp7_Lm4 psql -h db.example.invalid"))
                .isEqualTo("PGPASSWORD=[REDACTED] psql -h db.example.invalid");
    }

    @Test
    void valuesComeFromCredentialNamedSettingsInEverySource() {
        StandardEnvironment environment = new StandardEnvironment();
        Map<String, Object> file = new LinkedHashMap<>();
        file.put("sba.judge.api-key", "${FIXTURE_JUDGE_VALUE}");
        file.put("sba.auth.api-token", "F1xtureAuthTokenValue01");
        file.put("spring.datasource.password", "F1xture-Db-Passw0rd");
        file.put("sba.local-ai.api-key", "lm-studio");
        file.put("sba.local-ai.max-tokens", "F1xtureNotACredential1");
        file.put("sba.judge.provider", "F1xtureProviderName01");
        file.put("server.port", "8766");
        Map<String, Object> process = new LinkedHashMap<>();
        process.put("TYPESAFE_API_KEY", "F1xtureEnvStyleKey0001");
        process.put("SBA_SIGNING_PASSPHRASE", "F1xture passphrase with spaces");
        process.put("FIXTURE_JUDGE_VALUE", "F1xtureResolvedJudgeKey");
        process.put("SBA_BROKEN_TOKEN", "${FIXTURE_MISSING_VALUE}");
        environment.getPropertySources().addFirst(new MapPropertySource("fixture-file", file));
        environment.getPropertySources().addFirst(new MapPropertySource("fixture-process", process));

        RedactionService service = new RedactionService(new IngestionProperties(), environment);
        String display = String.join(
                "\n",
                new ArrayList<Object>(file.values()).toString(),
                process.values().toString());
        String redacted = service.redact(display);

        assertThat(redacted)
                .doesNotContain(
                        "F1xtureAuthTokenValue01",
                        "F1xture-Db-Passw0rd",
                        "F1xtureEnvStyleKey0001",
                        "F1xture passphrase with spaces",
                        "F1xtureResolvedJudgeKey")
                .contains("lm-studio", "F1xtureNotACredential1", "F1xtureProviderName01", "8766");
    }

    @Test
    void maskingCanBeSwitchedOffOnItsOwn() {
        StandardEnvironment environment = new StandardEnvironment();
        environment
                .getPropertySources()
                .addFirst(new MapPropertySource("fixture", Map.of("sba.judge.api-key", SECRET)));
        IngestionProperties properties = new IngestionProperties();
        properties.setMaskConfiguredSecrets(false);
        String text = "| SBA_SETTING | " + SECRET + " |";

        assertThat(new RedactionService(properties, environment).redact(text)).isEqualTo(text);
        assertThat(new RedactionService(new IngestionProperties(), environment).redact(text))
                .isEqualTo("| SBA_SETTING | [REDACTED] |");
    }

    @Test
    void maskingALargeTextWithManyValuesStaysLinear() {
        List<String> values = new ArrayList<>();
        for (int index = 0; index < 200; index++) {
            values.add("F1xture-" + Integer.toHexString(index * 7919) + "-Value-" + index);
        }
        ConfiguredSecretMasker many = ConfiguredSecretMasker.ofValues(values);
        // Every position starts a registered form, and none completes one.
        String text = "F1xture-".repeat(2_500_000 / 8) + values.get(199);

        String masked = assertTimeoutPreemptively(Duration.ofSeconds(5), () -> many.mask(text));

        assertThat(masked).endsWith("F1xture-[REDACTED]").doesNotContain(values.get(199));
    }
}
