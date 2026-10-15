package dev.nathan.sbaagentic.recording;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import dev.nathan.sbaagentic.recording.internal.application.RedactionService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Displayed service configuration (launchd plists, plutil, launchctl, env argv) loses its credential values. */
class ConfigSecretRedactionTest {
    // Synthetic value in the apikey_<hex>_<hex> provider shape; never a real credential.
    private static final String FAKE_KEY = "apikey_0123456789abcdef0123456789abcdef_fedcba9876543210fedcba9876543210";
    private final RedactionService redactor = new RedactionService(new IngestionProperties());

    static Stream<Arguments> configDisplays() {

        return Stream.of(
                Arguments.of(
                        "plutil -p entry",
                        "\"SBA_JUDGE_API_KEY\" => \"" + FAKE_KEY + "\"",
                        "\"SBA_JUDGE_API_KEY\" => \"[REDACTED]\""),
                Arguments.of("plutil -p environment block", """
                        {
                          "EnvironmentVariables" => {
                            "PATH" => "/usr/bin:/bin"
                            "SBA_JUDGE_API_KEY" => "F4KE7 judge value"
                          }
                          "Label" => "com.example.blackbox"
                        }""", """
                        {
                          "EnvironmentVariables" => {
                            "PATH" => "/usr/bin:/bin"
                            "SBA_JUDGE_API_KEY" => "[REDACTED]"
                          }
                          "Label" => "com.example.blackbox"
                        }"""),
                Arguments.of("launchctl print environment", """
                        \tenvironment = {
                        \t\tPATH => /usr/bin:/bin
                        \t\tSBA_JUDGE_API_KEY => F4KE7_launchctl_value
                        \t}""", """
                        \tenvironment = {
                        \t\tPATH => /usr/bin:/bin
                        \t\tSBA_JUDGE_API_KEY => [REDACTED]
                        \t}"""),
                Arguments.of("plist XML environment", """
                        <key>EnvironmentVariables</key>
                        <dict>
                            <key>SBA_PORT</key>
                            <string>8766</string>
                            <key>SBA_JUDGE_API_KEY</key>
                            <string>F4KE7 judge value</string>
                        </dict>""", """
                        <key>EnvironmentVariables</key>
                        <dict>
                            <key>SBA_PORT</key>
                            <string>8766</string>
                            <key>SBA_JUDGE_API_KEY</key>
                            <string>[REDACTED]</string>
                        </dict>"""),
                Arguments.of(
                        "compact plist data value",
                        "<key>DB_PASSWORD</key><data>RjRLRTc=</data>",
                        "<key>DB_PASSWORD</key><data>[REDACTED]</data>"),
                Arguments.of(
                        "unterminated plist value consumes the rest of the scalar",
                        "<key>SBA_JUDGE_API_KEY</key>\n<string>F4KE7 clipped value",
                        "<key>SBA_JUDGE_API_KEY</key>\n<string>[REDACTED]"),
                Arguments.of(
                        "plist value behind a line-number gutter",
                        "    12→    <key>SBA_JUDGE_API_KEY</key>\n    13→    <string>F4KE7 judge value</string>",
                        "    12→    <key>SBA_JUDGE_API_KEY</key>\n    13→    <string>[REDACTED]</string>"),
                Arguments.of(
                        "plist value in grep context output",
                        "12:    <key>SBA_JUDGE_API_KEY</key>\n13-    <string>F4KE7 judge value</string>",
                        "12:    <key>SBA_JUDGE_API_KEY</key>\n13-    <string>[REDACTED]</string>"),
                Arguments.of(
                        "JSON-encoded plutil output",
                        "{\"stdout\":\"{\\n  \\\"SBA_JUDGE_API_KEY\\\" => \\\"" + FAKE_KEY
                                + "\\\"\\n}\",\"stderr\":\"\"}",
                        "{\"stdout\":\"{\\n  \\\"SBA_JUDGE_API_KEY\\\" => \\\"[REDACTED]\\\"\\n}\",\"stderr\":\"\"}"),
                Arguments.of(
                        "JSON-encoded plist XML",
                        "{\"stdout\":\"<key>SBA_JUDGE_API_KEY</key>\\n\\t<string>" + FAKE_KEY + "</string>\"}",
                        "{\"stdout\":\"<key>SBA_JUDGE_API_KEY</key>\\n\\t<string>[REDACTED]</string>\"}"),
                Arguments.of(
                        "JSON-encoded assignment with spaces in its value",
                        "{\"stdout\":\"{\\\"api_key\\\": \\\"F4KE7 SPACE VALUE\\\", \\\"note\\\": \\\"readable\\\"}\"}",
                        "{\"stdout\":\"{\\\"api_key\\\": \\\"[REDACTED]\\\", \\\"note\\\": \\\"readable\\\"}\"}"),
                Arguments.of(
                        "doubly JSON-encoded assignment",
                        "before \\\\\\\"password\\\\\\\": \\\\\\\"F4KE7 double\\\\\\\" after",
                        "before \\\\\\\"password\\\\\\\": \\\\\\\"[REDACTED]\\\\\\\" after"),
                Arguments.of(
                        "JSON-encoded value containing encoded quotes",
                        "\\\"password\\\": \\\"F4KE \\\\\\\"QUOTED\\\\\\\" VALUE\\\" after",
                        "\\\"password\\\": \\\"[REDACTED]\\\" after"),
                Arguments.of(
                        "JSON-encoded launchctl print",
                        "{\"stdout\":\"\\tenvironment = {\\n\\t\\tSBA_JUDGE_API_KEY => " + FAKE_KEY
                                + "\\n\\t\\tPATH => /usr/bin:/bin\\n\\t}\"}",
                        "{\"stdout\":\"\\tenvironment = {\\n\\t\\tSBA_JUDGE_API_KEY => [REDACTED] => /usr/bin:/bin\\n\\t}\"}"),
                Arguments.of(
                        "env argv on a command line",
                        "/usr/bin/env SBA_X_TOKEN=F4KE7_ENV_VALUE java -jar app.jar",
                        "/usr/bin/env SBA_X_TOKEN=[REDACTED] java -jar app.jar"),
                Arguments.of(
                        "env argv in plist ProgramArguments",
                        "<string>/usr/bin/env</string>\n<string>SBA_X_TOKEN=F4KE7_ENV_VALUE</string>\n<string>java</string>",
                        "<string>/usr/bin/env</string>\n<string>SBA_X_TOKEN=[REDACTED]\n<string>java</string>"),
                Arguments.of(
                        "bare provider-shaped key",
                        "curl -H 'X-Judge: " + FAKE_KEY + "' https://judge.example.test",
                        "curl -H 'X-Judge: [REDACTED]' https://judge.example.test"),
                Arguments.of(
                        "bare key after a JSON-encoded newline",
                        "{\"stdout\":\"printenv follows\\n" + FAKE_KEY + "\\n\"}",
                        "{\"stdout\":\"printenv follows\\n[REDACTED]\\n\"}"),
                Arguments.of("bareword key with a quoted value", "password => 'F4KE7'", "password => '[REDACTED]'"),
                Arguments.of("compact bareword pair stays conservative", "password=>F4KE7", "password=>[REDACTED]"),
                Arguments.of("Ruby symbol key", "{:api_key=>\"F4KE7\"}", "{:api_key=>\"[REDACTED]\"}"),
                Arguments.of("numeric password stays secret", "password: 1234", "password: [REDACTED]"),
                Arguments.of("singular numeric token stays secret", "token: 123456", "token: [REDACTED]"),
                Arguments.of(
                        "token count with a non-numeric value stays secret",
                        "context_tokens: F4KE7",
                        "context_tokens: [REDACTED]"),
                Arguments.of(
                        "token count beyond twelve digits stays secret",
                        "max_tokens=1234567890123",
                        "max_tokens=[REDACTED]"),
                Arguments.of(
                        "token count glued to more value stays secret",
                        "max_tokens=1234;F4KE7",
                        "max_tokens=[REDACTED]"),
                Arguments.of(
                        "quoted value with an attached suffix",
                        "password=\"F4KE7\"F4KE7_TAIL next",
                        "password=\"[REDACTED]\" next"),
                Arguments.of(
                        "JSON-encoded value with an attached suffix",
                        "\\\"password\\\": \\\"F4KE7\\\"F4KE7_TAIL next",
                        "\\\"password\\\": \\\"[REDACTED]\\\" next"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("configDisplays")
    void removesCredentialValuesFromConfigurationDisplays(String display, String input, String expected) {
        assertThat(redactor.redact(input)).isEqualTo(expected);
        assertThat(redactor.redactDeep(Map.of("stdout", List.of(input))))
                .isEqualTo(Map.of("stdout", List.of(expected)));
        assertThat(redactor.redactForExport(input))
                .doesNotContain("F4KE", "RjRLRTc", "0123456789abcdef", "fedcba9876543210");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "context_tokens: 1234",
                "\"max_tokens\": 4096, \"temperature\": 0.2",
                "max_tokens",
                "usage: input_tokens=1200 output_tokens=345 cache_read_input_tokens=0",
                "\\\"promptTokenCount\\\": 87",
                "Set the apikey in your shell profile, not in the plist.",
                "apikey_header and apikey_v2 are field names; apikey_cafe is a fixture name",
                "tokens.forEach(token => validate(token));",
                "public string Token => _token;",
                "if (!token) { return null; }",
                "\"PATH\" => \"/usr/bin:/bin\"",
                "<key>Label</key>\n<string>com.example.blackbox</string>",
                "<key>KeepAlive</key>\n<true/>",
                "<key>tokenCount</key>\n<integer>42</integer>"
            })
    void preservesBenignCodeConfigurationAndTokenCounts(String text) {
        assertThat(redactor.redact(text)).isEqualTo(text);
        assertThat(redactor.redactDeep(Map.of("stdout", List.of(text)))).isEqualTo(Map.of("stdout", List.of(text)));
    }

    @Test
    void nearMissConfigurationTextHasBoundedScanWork() {
        String nearMisses = "<key>" + "k".repeat(200) + "\n"
                + "<key>SBA_JUDGE_API_KEY</key>" + "\\n\\t".repeat(150) + "<true/>\n"
                + "<key>SBA_JUDGE_API_KEY</key>" + " ".repeat(300) + "<string>x</string>\n"
                + "tokens.map(token => token.trim()) "
                + "apikey_" + "0123456789abcdef".repeat(40) + "g "
                + "\\".repeat(64) + "\"token" + "\\".repeat(64) + "\" => "
                + "\"SBA_X_TOKEN\" = > \"x\" " + "context_tokens: 12 ";
        StringBuilder text = new StringBuilder(2_100_000);
        while (text.length() < 2_000_000) {
            text.append(nearMisses);
        }
        String oneScalar = text.toString();
        List<String> scalars = new ArrayList<>();
        for (int start = 0; start + 49_000 <= oneScalar.length(); start += 49_000) {
            scalars.add(oneScalar.substring(start, start + 49_000));
        }

        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            assertThat(redactor.redact(oneScalar)).endsWith(" …[truncated]");
            assertThat(redactor.redactForExport(oneScalar)).endsWith(" …[truncated]");
            assertThat((List<?>) redactor.redactDeep(scalars)).hasSize(scalars.size());
        });
    }
}
