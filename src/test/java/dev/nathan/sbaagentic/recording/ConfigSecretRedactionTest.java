package dev.nathan.sbaagentic.recording;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.fasterxml.jackson.databind.ObjectMapper;
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
    // Every synthetic credential value above; none may survive export redaction.
    private static final String[] SYNTHETIC_VALUES = {
        "F4KE", "f4ce7", "40613", "RjRLRTc", "0123456789abcdef", "fedcba9876543210"
    };
    private static final ObjectMapper JSON = new ObjectMapper();
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
                        "{\"stdout\":\"\\tenvironment = {\\n\\t\\tSBA_JUDGE_API_KEY => [REDACTED]\\n\\t\\tPATH => /usr/bin:/bin\\n\\t}\"}"),
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
                        "an inline => pair in prose keeps the rest of its line",
                        "Set `SBA_JUDGE_API_KEY => F4KE7` in the plist, then restart the service.",
                        "Set `SBA_JUDGE_API_KEY => [REDACTED] in the plist, then restart the service."),
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
                "<key>tokenCount</key>\n<integer>42</integer>",
                "<key>max_tokens</key><integer>4096</integer>",
                "<key>SBA_TOKEN_SCOPES</key>\n<array>\n  <string>read</string>\n</array>",
                "+    <key>Label</key>\n+    <string>com.example.blackbox</string>",
                "/fixture/a.plist:3:<key>Label</key>\n/fixture/a.plist-4-<string>com.example.blackbox</string>",
                "\\t\\tPATH => /usr/bin:/bin\\n\\t\\tSBA_PORT => 8766\\n\\t}",
                "const ENTER_KEY = 13;",
                "private static final String CACHE_KEY = \"summary\";",
                "fetch(url, { credentials: 'include' })",
                "xhr.withCredentials = true;",
                "EVENT_KEY=\"$(printf '%s' \"$EVENT_TYPE\" | tr -cd '[:alnum:]')\"",
                "if self.headers.get(\"Authorization\") != FAKE_KEY:\n    self.respond({\"error\": \"auth failed\"}, 401)",
                "if str(error) == \"credential_unavailable\":\n    return \"paused\"",
                "Set `SBA_PORT => 8766` in the plist, then restart the service.",
                "{\"credential-management\":require(\"./features/credential-management\"),\"w-credentialless-x\":o}",
                "\"WebAuthn.credentialAdded\": WebAuthn.credentialAddedPayload;"
            })
    void preservesBenignCodeConfigurationAndTokenCounts(String text) {
        assertThat(redactor.redact(text)).isEqualTo(text);
        assertThat(redactor.redactDeep(Map.of("stdout", List.of(text)))).isEqualTo(Map.of("stdout", List.of(text)));
    }

    /**
     * Synthetic displays that must lose every credential the same way whether raw or JSON-encoded up
     * to four times: the capture hook stores an object tool result as jq {@code tojson} text, and a
     * transcript or log that quotes that text encodes it again.
     */
    static Stream<Arguments> displaysAtEveryEncodingLevel() {

        return Stream.of(
                Arguments.of("launchctl print with adjacent credentials", """
                        \tenvironment = {
                        \t\tPATH => /usr/bin:/bin
                        \t\tSBA_A_PASSWORD => F4KE7first
                        \t\tSBA_DB_PASSWORD => F4KE7second
                        \t\tSBA_JUDGE_API_KEY => F4KE7third
                        \t\tSBA_PORT => 8766
                        \t}""", """
                        \tenvironment = {
                        \t\tPATH => /usr/bin:/bin
                        \t\tSBA_A_PASSWORD => [REDACTED]
                        \t\tSBA_DB_PASSWORD => [REDACTED]
                        \t\tSBA_JUDGE_API_KEY => [REDACTED]
                        \t\tSBA_PORT => 8766
                        \t}"""),
                Arguments.of("env listing with adjacent credentials", """
                        PATH=/usr/bin:/bin
                        SBA_A_PASSWORD=F4KE7first
                        SBA_DB_PASSWORD=F4KE7second
                        HOME=/fixture/home""", """
                        PATH=/usr/bin:/bin
                        SBA_A_PASSWORD=[REDACTED]
                        SBA_DB_PASSWORD=[REDACTED]
                        HOME=/fixture/home"""),
                Arguments.of("launchctl print value with spaces and punctuation", """
                        \t\tSBA_X_PASSWORD => F4KE7 two words, a comma] and {braces}
                        \t\tPATH => /usr/bin:/bin""", """
                        \t\tSBA_X_PASSWORD => [REDACTED]
                        \t\tPATH => /usr/bin:/bin"""),
                Arguments.of("plutil -p data values", """
                        {
                          "SBA_X_TOKEN" => {length = 16, bytes = 0xf4ce7f4ce7f4ce7f4ce7f4ce7f4ce7f4}
                          "api_token" => {length = 1024, bytes = 0xf4ce7f4c e7f4ce7f ... f4ce7f4c e7f4ce7f}
                          "PATH" => "/usr/bin:/bin"
                        }""", """
                        {
                          "SBA_X_TOKEN" => [REDACTED]
                          "api_token" => [REDACTED]
                          "PATH" => "/usr/bin:/bin"
                        }"""),
                Arguments.of("grep -rn context with path gutters", """
                        /fixture/LaunchAgents/com.example.blackbox.plist:12:    <key>SBA_JUDGE_API_KEY</key>
                        /fixture/LaunchAgents/com.example.blackbox.plist-13-    <string>F4KE7 grep value</string>
                        /fixture/LaunchAgents/com.example.blackbox.plist-14-    <key>SBA_PORT</key>""", """
                        /fixture/LaunchAgents/com.example.blackbox.plist:12:    <key>SBA_JUDGE_API_KEY</key>
                        /fixture/LaunchAgents/com.example.blackbox.plist-13-    <string>[REDACTED]</string>
                        /fixture/LaunchAgents/com.example.blackbox.plist-14-    <key>SBA_PORT</key>"""),
                Arguments.of("grep -r context without line numbers", """
                        /fixture/LaunchAgents/com.example.blackbox.plist:    <key>SBA_JUDGE_API_KEY</key>
                        /fixture/LaunchAgents/com.example.blackbox.plist-    <string>F4KE7 grep value</string>""", """
                        /fixture/LaunchAgents/com.example.blackbox.plist:    <key>SBA_JUDGE_API_KEY</key>
                        /fixture/LaunchAgents/com.example.blackbox.plist-    <string>[REDACTED]</string>"""),
                Arguments.of("git diff adding a credential", """
                        @@ -10,6 +10,8 @@
                             <key>SBA_PORT</key>
                             <string>8766</string>
                        +    <key>SBA_JUDGE_API_KEY</key>
                        +    <string>F4KE7 added</string>
                         </dict>""", """
                        @@ -10,6 +10,8 @@
                             <key>SBA_PORT</key>
                             <string>8766</string>
                        +    <key>SBA_JUDGE_API_KEY</key>
                        +    <string>[REDACTED]</string>
                         </dict>"""),
                Arguments.of("git diff changing a credential", """
                             <key>SBA_JUDGE_API_KEY</key>
                        -    <string>F4KE7 old</string>
                        +    <string>F4KE7 new</string>
                             <key>SBA_PORT</key>
                             <string>8766</string>""", """
                             <key>SBA_JUDGE_API_KEY</key>
                        -    <string>[REDACTED]</string>
                        +    <string>[REDACTED]</string>
                             <key>SBA_PORT</key>
                             <string>8766</string>"""),
                Arguments.of(
                        "Edit tool structuredPatch serialized as JSON",
                        "{\"structuredPatch\":[{\"oldStart\":12,\"oldLines\":5,\"newStart\":12,\"newLines\":5,\"lines\":["
                                + "\"     <key>SBA_JUDGE_API_KEY</key>\",\"-    <string>F4KE7 old</string>\","
                                + "\"+    <string>F4KE7 new</string>\",\"     <key>SBA_PORT</key>\",\"     <string>8766</string>\"]}]}",
                        "{\"structuredPatch\":[{\"oldStart\":12,\"oldLines\":5,\"newStart\":12,\"newLines\":5,\"lines\":["
                                + "\"     <key>SBA_JUDGE_API_KEY</key>\",\"-    <string>[REDACTED]</string>\","
                                + "\"+    <string>[REDACTED]</string>\",\"     <key>SBA_PORT</key>\",\"     <string>8766</string>\"]}]}"),
                Arguments.of(
                        "Python list of plist lines",
                        "['    <key>SBA_JUDGE_API_KEY</key>\\n', '    <string>F4KE7 listed</string>\\n']",
                        "['    <key>SBA_JUDGE_API_KEY</key>\\n', '    <string>[REDACTED]</string>\\n']"),
                Arguments.of("XML comment between key and value", """
                        <key>SBA_JUDGE_API_KEY</key>
                        <!-- rotated 2026-10 -->
                        <string>F4KE7 commented</string>""", """
                        <key>SBA_JUDGE_API_KEY</key>
                        <!-- rotated 2026-10 -->
                        <string>[REDACTED]</string>"""),
                Arguments.of("plist numbers under secret keys", """
                        <key>SBA_PIN_PASSWORD</key>
                        <integer>740613</integer>
                        <key>SBA_X_TOKEN</key>
                        <real>7.40613</real>
                        <key>tokenCount</key>
                        <integer>42</integer>""", """
                        <key>SBA_PIN_PASSWORD</key>
                        <integer>[REDACTED]</integer>
                        <key>SBA_X_TOKEN</key>
                        <real>[REDACTED]</real>
                        <key>tokenCount</key>
                        <integer>42</integer>"""),
                Arguments.of("credential names in plutil -p", """
                        {
                          "SBA_DB_CREDENTIAL" => "F4KE7cred"
                          "SBA_PRIVATE_KEY" => "F4KE7pk"
                          "SBA_SIGNING_PASSPHRASE" => "F4KE7pass"
                          "SBA_JUDGE_KEY" => "F4KE7jk"
                          "SBA_PORT" => "8766"
                        }""", """
                        {
                          "SBA_DB_CREDENTIAL" => "[REDACTED]"
                          "SBA_PRIVATE_KEY" => "[REDACTED]"
                          "SBA_SIGNING_PASSPHRASE" => "[REDACTED]"
                          "SBA_JUDGE_KEY" => "[REDACTED]"
                          "SBA_PORT" => "8766"
                        }"""),
                Arguments.of("credential names in launchctl print", """
                        \t\tSBA_DB_CREDENTIAL => F4KE7cred
                        \t\tSBA_PRIVATE_KEY => F4KE7pk
                        \t\tSBA_SIGNING_PASSPHRASE => F4KE7pass
                        \t\tSTRIPE_KEY => F4KE7sk
                        \t\tSBA_PORT => 8766""", """
                        \t\tSBA_DB_CREDENTIAL => [REDACTED]
                        \t\tSBA_PRIVATE_KEY => [REDACTED]
                        \t\tSBA_SIGNING_PASSPHRASE => [REDACTED]
                        \t\tSTRIPE_KEY => [REDACTED]
                        \t\tSBA_PORT => 8766"""),
                Arguments.of("credential names in plist XML", """
                        <key>SBA_SIGNING_PASSPHRASE</key>
                        <string>F4KE7pass</string>
                        <key>STRIPE_KEY</key>
                        <string>F4KE7sk</string>
                        <key>SBA_PORT</key>
                        <string>8766</string>""", """
                        <key>SBA_SIGNING_PASSPHRASE</key>
                        <string>[REDACTED]</string>
                        <key>STRIPE_KEY</key>
                        <string>[REDACTED]</string>
                        <key>SBA_PORT</key>
                        <string>8766</string>"""),
                Arguments.of(
                        "credential names in env argv",
                        "/usr/bin/env SBA_JUDGE_KEY=F4KE7jk SBA_DB_PASSPHRASE=F4KE7pp SBA_DB_CREDENTIAL=F4KE7cr java -jar app.jar",
                        "/usr/bin/env SBA_JUDGE_KEY=[REDACTED] SBA_DB_PASSPHRASE=[REDACTED] SBA_DB_CREDENTIAL=[REDACTED]"
                                + " java -jar app.jar"),
                Arguments.of("credential names in JSON and YAML", """
                        {"env": {"STRIPE_KEY": "F4KE7sk", "SBA_DB_CREDENTIAL": "F4KE7cred", "db_passphrase": "F4KE7pp"}}
                        environment:
                          STRIPE_KEY: F4KE7yaml
                          SBA_PORT: 8766""", """
                        {"env": {"STRIPE_KEY": "[REDACTED]", "SBA_DB_CREDENTIAL": "[REDACTED]", "db_passphrase": "[REDACTED]"}}
                        environment:
                          STRIPE_KEY: [REDACTED]
                          SBA_PORT: 8766"""));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("displaysAtEveryEncodingLevel")
    void removesCredentialsFromADisplayAtEveryJsonEncodingLevel(String display, String input, String expected)
            throws Exception {
        String encodedInput = input;
        String encodedExpected = expected;
        for (int level = 0; level <= 4; level++) {
            assertThat(redactor.redact(encodedInput))
                    .as("%s, JSON-encoded %d times", display, level)
                    .isEqualTo(encodedExpected);
            assertThat(redactor.redactForExport(encodedInput))
                    .as("%s exported, JSON-encoded %d times", display, level)
                    .doesNotContain(SYNTHETIC_VALUES);
            encodedInput = jsonEncode(encodedInput);
            encodedExpected = jsonEncode(encodedExpected);
        }
        assertThat(redactor.redactDeep(Map.of("stdout", List.of(input))))
                .isEqualTo(Map.of("stdout", List.of(expected)));
    }

    static Stream<Arguments> encoderEscapes() {

        return Stream.of(
                Arguments.of(
                        "slash-escaped closing tags",
                        "<key>SBA_JUDGE_API_KEY<\\/key>\\n<string>F4KE7 php<\\/string>",
                        "<key>SBA_JUDGE_API_KEY<\\/key>\\n<string>[REDACTED]<\\/string>"),
                // A credential found only after decoding HTML-safe escapes keeps the decoded characters.
                Arguments.of(
                        "HTML-safe escapes in plist XML",
                        "\\u003ckey\\u003eSBA_JUDGE_API_KEY\\u003c/key\\u003e\\n\\u003cstring\\u003eF4KE7 go\\u003c/string\\u003e",
                        "<key>SBA_JUDGE_API_KEY</key>\\n<string>[REDACTED]</string>"),
                Arguments.of(
                        "unicode-escaped quotes in plutil -p output",
                        "\\u0022SBA_JUDGE_API_KEY\\u0022 => \\u0022F4KE7 quoted\\u0022",
                        "\"SBA_JUDGE_API_KEY\" => \"[REDACTED]\""),
                Arguments.of(
                        "HTML-safe escape inside =>",
                        "\\\"SBA_JUDGE_API_KEY\\\" =\\u003e \\\"F4KE7 arrow\\\"",
                        "\\\"SBA_JUDGE_API_KEY\\\" => \\\"[REDACTED]\\\""),
                Arguments.of(
                        "provider key after a percent-encoded quote", "q=%22" + FAKE_KEY + "%22", "q=%22[REDACTED]%22"),
                Arguments.of(
                        "provider key after a percent-encoded equals sign",
                        "key%3D" + FAKE_KEY + "&x=1",
                        "key%3D[REDACTED]&x=1"),
                Arguments.of(
                        "provider key after a unicode-escaped newline",
                        "first\\u000a" + FAKE_KEY,
                        "first\\u000a[REDACTED]"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("encoderEscapes")
    void removesCredentialsBehindOtherEncoderEscapes(String display, String input, String expected) {
        assertThat(redactor.redact(input)).isEqualTo(expected);
        assertThat(redactor.redactForExport(input)).doesNotContain(SYNTHETIC_VALUES);
    }

    @Test
    void exportKeepsDataKeysThatOnlyMentionCredentials() {
        // Export's whole-leaf fallback after a quoted credential key uses the strong words only.
        String protocol = "\"WebAuthn.credentialAdded\": WebAuthn.credentialAddedPayload;\n\"WebAuthn.enable\": {};";
        assertThat(redactor.redactForExport(protocol)).isEqualTo(protocol);
        assertThat(redactor.redactForExport("{\"SBA_DB_CREDENTIAL\": \"F4KE7\", \"note\": \"kept\"}"))
                .isEqualTo("{\"SBA_DB_CREDENTIAL\": \"[REDACTED]\", \"note\": \"kept\"}");
    }

    @Test
    void propertyListPairSplitAcrossListElementsLosesItsValue() {
        // An Edit tool result: each structuredPatch line is its own JSON string.
        Map<String, Object> patch = Map.of(
                "structuredPatch",
                List.of(Map.of(
                        "lines",
                        List.of(
                                "     <key>SBA_JUDGE_API_KEY</key>",
                                "-    <string>F4KE7 old</string>",
                                "+    <string>F4KE7 new</string>",
                                "     <key>SBA_PORT</key>",
                                "     <string>8766</string>"))));
        assertThat(redactor.redactDeep(patch))
                .isEqualTo(Map.of(
                        "structuredPatch",
                        List.of(Map.of(
                                "lines",
                                List.of(
                                        "     <key>SBA_JUDGE_API_KEY</key>",
                                        "-    <string>[REDACTED]</string>",
                                        "+    <string>[REDACTED]</string>",
                                        "     <key>SBA_PORT</key>",
                                        "     <string>8766</string>")))));
        // readlines() output keeps line endings, and a blank line may separate the pair.
        assertThat(redactor.redactDeep(List.of("<key>STRIPE_KEY</key>\n", "\n", "<string>F4KE7</string>\n")))
                .isEqualTo(List.of("<key>STRIPE_KEY</key>\n", "\n", "<string>[REDACTED]</string>\n"));
        assertThat(redactor.redactDeep(List.of("<key>Label</key>", "<string>com.example.blackbox</string>")))
                .isEqualTo(List.of("<key>Label</key>", "<string>com.example.blackbox</string>"));
    }

    // One repeated unit per display rule: each almost matches, so every rule scans as far as it can.
    private static final List<Arguments> NEAR_MISS_SHAPES = List.of(
            Arguments.of(
                    "bare values beside escaped line breaks",
                    "SBA_X_PASSWORD => " + "\\".repeat(40) + "x\\n\\tSBA_Y_TOKEN=" + "\\".repeat(33) + "q "),
            Arguments.of("launchctl values running to the line end", "\t\tSBA_X_TOKEN => " + "v, ".repeat(300) + "\n"),
            Arguments.of("unclosed plutil data values", "\"api_token\" => {length = " + "9".repeat(300) + " "),
            Arguments.of(
                    "property-list gaps",
                    "<key>SBA_JUDGE_API_KEY</key>" + "-".repeat(1023) + "<b> <key>SBA_JUDGE_API_KEY</key><!--"
                            + "-".repeat(300) + "> <key>password</key>\\n<!-- -->" + " ".repeat(1100) + "<string>"),
            Arguments.of(
                    "HTML-safe escapes",
                    "\\u003ckey\\u003e" + "k".repeat(100) + " \\\\u0022token\\u0022 =\\u003e \\u003c\\u003c "),
            Arguments.of(
                    "environment-style key names",
                    "A_".repeat(300) + "KEX=" + " SBA_" + "B_".repeat(100) + "KEY = 13 xCREDENTIAL: 1 "),
            Arguments.of(
                    "provider keys after encoded delimiters",
                    "%22apikey_" + "0123456789abcdef".repeat(30) + "g \\u000aapikey_" + "0123456789abcdef".repeat(30)
                            + "g "),
            Arguments.of(
                    "four-level escaped quotes",
                    "\\".repeat(15) + "\"token" + "\\".repeat(14) + "\" => " + "\\".repeat(16) + "\" "));

    static Stream<Arguments> nearMissShapes() {

        return NEAR_MISS_SHAPES.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("nearMissShapes")
    void eachDisplayRuleScansFourMegabytesOfNearMissesInBoundedTime(String shape, String unit) {
        // Below the 50,000-unit scan budget, so every character is scanned rather than clipped.
        List<String> scalars = scalars(unit, 4_000_000, 49_000);

        assertTimeoutPreemptively(Duration.ofSeconds(4), () -> {
            assertThat((List<?>) redactor.redactDeep(scalars)).hasSize(scalars.size());
            scalars.forEach(redactor::redactForExport);
        });
    }

    @Test
    void propertyListPairsAcrossManyListElementsHaveBoundedScanWork() {
        // Every element ends with an open secret key and every next element almost starts with a value.
        String unit = "x".repeat(900) + "<key>SBA_JUDGE_API_KEY</key>" + " ".repeat(1000);
        List<String> elements = new ArrayList<>(scalars(unit + " ".repeat(1023) + "<b>", 4_000_000, 1_000));

        assertTimeoutPreemptively(
                Duration.ofSeconds(4),
                () -> assertThat((List<?>) redactor.redactDeep(elements)).hasSize(elements.size()));
    }

    private static List<String> scalars(String unit, int totalChars, int scalarChars) {
        StringBuilder text = new StringBuilder(totalChars + unit.length());
        while (text.length() < totalChars) {
            text.append(unit);
        }
        List<String> scalars = new ArrayList<>();
        for (int start = 0; start + scalarChars <= text.length(); start += scalarChars) {
            scalars.add(text.substring(start, start + scalarChars));
        }

        return scalars;
    }

    private static String jsonEncode(String text) throws Exception {
        String quoted = JSON.writeValueAsString(text);

        return quoted.substring(1, quoted.length() - 1);
    }

    @Test
    void nearMissConfigurationTextHasBoundedScanWork() {
        String nearMisses = "<key>" + "k".repeat(200) + "\n"
                + "<key>SBA_JUDGE_API_KEY</key>" + "\\n\\t".repeat(150) + "<true/>\n"
                + "<key>SBA_JUDGE_API_KEY</key>" + " ".repeat(300) + "<string>x</string>\n"
                + "tokens.map(token => token.trim()) "
                + "apikey_" + "0123456789abcdef".repeat(40) + "g "
                + "\\".repeat(64) + "\"token" + "\\".repeat(64) + "\" => "
                + "\"SBA_X_TOKEN\" = > \"x\" " + "context_tokens: 12 "
                + NEAR_MISS_SHAPES.stream()
                        .map(shape -> shape.get()[1].toString())
                        .reduce("", String::concat);
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
