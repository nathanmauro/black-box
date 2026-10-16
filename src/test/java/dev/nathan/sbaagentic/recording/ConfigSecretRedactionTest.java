package dev.nathan.sbaagentic.recording;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nathan.sbaagentic.recording.internal.application.RedactionService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
                "\"WebAuthn.credentialAdded\": WebAuthn.credentialAddedPayload;",
                "tokens.map(\n    token => token.trim()\n)",
                "## Mapping\n- `token => token.trim()` drops the padding",
                "\tpassword => { return check(password); }",
                "  secret => (secret ?? '').length,",
                "SSH_KEY=$HOME/.ssh/id_ed25519",
                "STRIPE_KEY=\"${STRIPE_KEY}\"",
                "claude --max-tokens 4096 --model local",
                "sha_apikey_0123456789abcdef is a fixture name",
                "Pass --api-key or --auth-token to the CLI, and --secret-id with the reference.",
                "aws secretsmanager get-secret-value --secret-id prod/judge --query SecretString",
                "command = [\"aws\", \"--next-token\", token, \"--page-token\", \"next\"]",
                "parser.add_argument(\"--api-key\", help=\"the judge key\", default=None)"
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
                          SBA_PORT: 8766"""),
                Arguments.of("launchctl print values holding quotes and backslashes", """
                        \t\tSBA_DB_PASSWORD => F4KE7qa"quoted tail rest
                        \t\tSBA_JUDGE_API_KEY => F4KE7qb_9x\\"escaped quote tail
                        \t\tSBA_X_TOKEN => F4KE7qc\\\\"backslash quote tail
                        \t\tSBA_Y_TOKEN => F4KE7qd\\nliteral escape tail
                        \t\tSBA_Z_TOKEN => F4KE7qe ends with a backslash \\
                        \t\tPATH => /usr/bin:/bin""", """
                        \t\tSBA_DB_PASSWORD => [REDACTED]
                        \t\tSBA_JUDGE_API_KEY => [REDACTED]
                        \t\tSBA_X_TOKEN => [REDACTED]
                        \t\tSBA_Y_TOKEN => [REDACTED]
                        \t\tSBA_Z_TOKEN => [REDACTED]
                        \t\tPATH => /usr/bin:/bin"""),
                Arguments.of("launchctl print entries named in any letter case", """
                        \t\tJevApiKey => F4KE7mixed
                        \t\tdb_password => F4KE7lower two words
                        \t\tjudgeToken => F4KE7camel
                        \t\tgithub_token => F4KE7gh
                        \t\tsba.judge.password => F4KE7dots
                        \t\tdb_credential => F4KE7cred
                        \t\tPATH => /usr/bin:/bin""", """
                        \t\tJevApiKey => [REDACTED]
                        \t\tdb_password => [REDACTED]
                        \t\tjudgeToken => [REDACTED]
                        \t\tgithub_token => [REDACTED]
                        \t\tsba.judge.password => [REDACTED]
                        \t\tdb_credential => [REDACTED]
                        \t\tPATH => /usr/bin:/bin"""),
                Arguments.of("launchctl print behind line gutters and quote marks", """
                        24:\t\tSBA_DB_PASSWORD => F4KE7gutter tail word
                        /fixture/launchctl.txt:25:\t\tSBA_X_TOKEN => F4KE7path tail
                        /fixture/launchctl.txt-26-\t\tSBA_V_TOKEN => F4KE7context tail
                            27\t\t\tSBA_Y_PASSWORD => F4KE7catn tail
                            28→\t\tSBA_Z_SECRET => F4KE7read tail
                        > \t\tSBA_Q_PASSWORD => F4KE7quote tail, and more
                        29-\t\tSBA_W_PASSWORD => F4KE7ctx tail""", """
                        24:\t\tSBA_DB_PASSWORD => [REDACTED]
                        /fixture/launchctl.txt:25:\t\tSBA_X_TOKEN => [REDACTED]
                        /fixture/launchctl.txt-26-\t\tSBA_V_TOKEN => [REDACTED]
                            27\t\t\tSBA_Y_PASSWORD => [REDACTED]
                            28→\t\tSBA_Z_SECRET => [REDACTED]
                        > \t\tSBA_Q_PASSWORD => [REDACTED]
                        29-\t\tSBA_W_PASSWORD => [REDACTED]"""),
                Arguments.of("Markdown before an entry, as a model's summary echoes one", """
                        ## JevApiKey => F4KE7heading words
                        - judgeToken => F4KE7bullet words
                        1. db_password => F4KE7numbered words
                        > - SBA_V_PASSWORD => F4KE7quoted list words
                        **SBA_X_TOKEN => F4KE7bold** and the rest of the line
                        `judgeToken => F4KE7code` is what launchctl print shows""", """
                        ## JevApiKey => [REDACTED]
                        - judgeToken => [REDACTED]
                        1. db_password => [REDACTED]
                        > - SBA_V_PASSWORD => [REDACTED]
                        **SBA_X_TOKEN => [REDACTED]** and the rest of the line
                        `judgeToken => [REDACTED]` is what launchctl print shows"""),
                Arguments.of("multi-line launchctl print value", """
                        \tenvironment = {
                        \t\tSBA_X_TOKEN => F4KE7first line
                        F4KE7second line
                        \t\tPATH => /usr/bin:/bin
                        \t}""", """
                        \tenvironment = {
                        \t\tSBA_X_TOKEN => [REDACTED]
                        \t\tPATH => /usr/bin:/bin
                        \t}"""),
                Arguments.of("plutil -p values holding unescaped quotes", """
                        {
                          "SBA_DB_PASSWORD" => "F4KE7q1"inner quote tail"
                          "SBA_X_TOKEN" => "F4KE7q2", "comma tail"
                          "PATH" => "/usr/bin:/bin"
                        }""", """
                        {
                          "SBA_DB_PASSWORD" => "[REDACTED]"
                          "SBA_X_TOKEN" => "[REDACTED]"
                          "PATH" => "/usr/bin:/bin"
                        }"""),
                Arguments.of("dollar-sign values in launchd displays and env listings", """
                        \t\tSBA_SIGNING_KEY => $F4KE7dollar
                        \t\tSBA_DB_CREDENTIAL => $2b$10$F4KE7bcrypt
                        \t\tSTRIPE_KEY => ${F4KE7braces}
                        SBA_HASH_KEY=$2b$10$F4KE7envBcrypt
                          "SBA_SIGNING_KEY" => "$F4KE7plutil\"""", """
                        \t\tSBA_SIGNING_KEY => [REDACTED]
                        \t\tSBA_DB_CREDENTIAL => [REDACTED]
                        \t\tSTRIPE_KEY => [REDACTED]
                        SBA_HASH_KEY=[REDACTED]
                          "SBA_SIGNING_KEY" => "[REDACTED]\""""),
                Arguments.of("provider keys glued to other text", """
                        strings output: Hapikey_0123456789abcdef0123456789abcdef_fedcba9876543210fedcba9876543210
                        apikey_0123456789abcdef0123456789abcdef_fedcba9876543210fedcba9876543210PID\tStatus\tLabel
                        echoed apikey_0123456789abcdef0123456789abcdefready""", """
                        strings output: H[REDACTED]
                        [REDACTED]PID\tStatus\tLabel
                        echoed [REDACTED]ready"""),
                Arguments.of(
                        "provider key inside binary property-list bytes",
                        "bplist00Ô\u0001\u0002_\u0010H" + FAKE_KEY + "\u0008\u0009",
                        "bplist00Ô\u0001\u0002_\u0010H[REDACTED]\u0008\u0009"),
                Arguments.of("credential flags in plist ProgramArguments", """
                        <key>ProgramArguments</key>
                        <array>
                            <string>/usr/bin/java</string>
                            <string>--api-key</string>
                            <string>F4KE7argXml</string>
                            <string>--port</string>
                            <string>8766</string>
                        </array>""", """
                        <key>ProgramArguments</key>
                        <array>
                            <string>/usr/bin/java</string>
                            <string>--api-key</string>
                            <string>[REDACTED]</string>
                            <string>--port</string>
                            <string>8766</string>
                        </array>"""),
                Arguments.of("credential flags in plutil -p arguments", """
                          "ProgramArguments" => [
                            0 => "/usr/bin/java"
                            1 => "--api-key"
                            2 => "F4KE7argPlutil"
                            3 => "--port"
                            4 => "8766"
                          ]""", """
                          "ProgramArguments" => [
                            0 => "/usr/bin/java"
                            1 => "--api-key"
                            2 => "[REDACTED]"
                            3 => "--port"
                            4 => "8766"
                          ]"""),
                Arguments.of("credential flags in launchctl print and PlistBuddy arguments", """
                        \targuments = {
                        \t\t/usr/bin/java
                        \t\t--api-key
                        \t\tF4KE7argLaunchctl
                        \t\t--port
                        \t}
                            ProgramArguments = Array {
                                --db-password
                                F4KE7argBuddy two words
                                --port
                            }""", """
                        \targuments = {
                        \t\t/usr/bin/java
                        \t\t--api-key
                        \t\t[REDACTED]
                        \t\t--port
                        \t}
                            ProgramArguments = Array {
                                --db-password
                                [REDACTED]
                                --port
                            }"""),
                Arguments.of("credential flags on command lines and in argument lists", """
                        /usr/bin/java -jar app.jar --api-key F4KE7argCli --db-password 'F4KE7 quoted' --port 8766
                        {"args":["--client-secret","F4KE7argJson","--port","8766"]}
                        (
                            "--auth-token",
                            "F4KE7 argDefaults",
                            "--port"
                        )""", """
                        /usr/bin/java -jar app.jar --api-key [REDACTED] --db-password '[REDACTED]' --port 8766
                        {"args":["--client-secret","[REDACTED]","--port","8766"]}
                        (
                            "--auth-token",
                            "[REDACTED]",
                            "--port"
                        )"""),
                Arguments.of("commands that write a launchd credential", """
                        plutil -replace EnvironmentVariables.SBA_DB_PASSWORD -string 'F4KE7 replace' /fixture/x.plist
                        /usr/libexec/PlistBuddy -c "Set :EnvironmentVariables:SBA_DB_PASSWORD F4KE7 buddy set" /fixture/x.plist
                        /usr/libexec/PlistBuddy -c 'Add :EnvironmentVariables:STRIPE_KEY string F4KE7buddyAdd' /fixture/x.plist
                        launchctl setenv SBA_DB_PASSWORD F4KE7setenv
                        defaults write com.example.blackbox ApiToken -string F4KE7defaults
                        security add-generic-password -a fixture -s jev -w F4KE7security -U""", """
                        plutil -replace EnvironmentVariables.SBA_DB_PASSWORD -string '[REDACTED]' /fixture/x.plist
                        /usr/libexec/PlistBuddy -c "Set :EnvironmentVariables:SBA_DB_PASSWORD [REDACTED]" /fixture/x.plist
                        /usr/libexec/PlistBuddy -c 'Add :EnvironmentVariables:STRIPE_KEY string [REDACTED]' /fixture/x.plist
                        launchctl setenv SBA_DB_PASSWORD [REDACTED]
                        defaults write com.example.blackbox ApiToken -string [REDACTED]
                        security add-generic-password -a fixture -s jev -w [REDACTED] -U"""),
                Arguments.of("PlistBuddy Print values with spaces", """
                        Dict {
                            Label = com.example.blackbox
                            EnvironmentVariables = Dict {
                                SBA_DB_PASSWORD = F4KE7spaced two words
                                SBA_PORT = 8766
                            }
                        }""", """
                        Dict {
                            Label = com.example.blackbox
                            EnvironmentVariables = Dict {
                                SBA_DB_PASSWORD = [REDACTED]
                                SBA_PORT = 8766
                            }
                        }"""),
                Arguments.of("credential names longer than forty characters", """
                        \tenvironment = {
                        \t\tSPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GITHUB_CLIENTSECRET => F4KE7long1
                        \t\tSPRING_DATASOURCE_HIKARI_DATA_SOURCE_PROPERTIES_PASSWORD => F4KE7long2 tail
                        \t\tPATH => /usr/bin:/bin
                        \t}
                          "SPRING_DATASOURCE_HIKARI_DATA_SOURCE_PROPERTIES_PASSWORD" => "F4KE7long3"
                        SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GITHUB_CLIENTSECRET=F4KE7long4
                        {"SPRING_DATASOURCE_HIKARI_DATA_SOURCE_PROPERTIES_PASSWORD": "F4KE7long5", "port": 5432}
                            SPRING_DATASOURCE_HIKARI_DATA_SOURCE_PROPERTIES_PASSWORD = F4KE7long6 tail
                        java -jar app.jar --spring-datasource-hikari-data-source-properties-password F4KE7long7 --port 8766""", """
                        \tenvironment = {
                        \t\tSPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GITHUB_CLIENTSECRET => [REDACTED]
                        \t\tSPRING_DATASOURCE_HIKARI_DATA_SOURCE_PROPERTIES_PASSWORD => [REDACTED]
                        \t\tPATH => /usr/bin:/bin
                        \t}
                          "SPRING_DATASOURCE_HIKARI_DATA_SOURCE_PROPERTIES_PASSWORD" => "[REDACTED]"
                        SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GITHUB_CLIENTSECRET=[REDACTED]
                        {"SPRING_DATASOURCE_HIKARI_DATA_SOURCE_PROPERTIES_PASSWORD": "[REDACTED]", "port": 5432}
                            SPRING_DATASOURCE_HIKARI_DATA_SOURCE_PROPERTIES_PASSWORD = [REDACTED]
                        java -jar app.jar --spring-datasource-hikari-data-source-properties-password [REDACTED] --port 8766"""),
                // A bare value inside a JSON string ends before the quote that closes the string.
                Arguments.of("credential flags and assignments inside JSON strings", """
                        {"scripts": {"start": "node server.js --api-key F4KE7flag", "dev": "vite"}}
                        {"env": "export DB_PASSWORD=F4KE7assign", "next": 1}
                        ["mysql --password F4KE7list"]""", """
                        {"scripts": {"start": "node server.js --api-key [REDACTED]", "dev": "vite"}}
                        {"env": "export DB_PASSWORD=[REDACTED]", "next": 1}
                        ["mysql --password [REDACTED]"]"""));
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

    /**
     * The same displays as the capture hook stores a Bash result: jq {@code tojson} of the tool
     * response object, so the display sits inside a JSON string beside other members, then encoded
     * again up to three more times.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("displaysAtEveryEncodingLevel")
    void removesCredentialsFromADisplayInsideAToolResultObjectAtEveryLevel(
            String display, String input, String expected) throws Exception {
        String encodedInput = toolResult(input);
        String encodedExpected = toolResult(expected);
        for (int level = 1; level <= 4; level++) {
            assertThat(redactor.redact(encodedInput))
                    .as("%s in a tool result, JSON-encoded %d times", display, level)
                    .isEqualTo(encodedExpected);
            assertThat(redactor.redactForExport(encodedInput))
                    .as("%s in a tool result exported, JSON-encoded %d times", display, level)
                    .doesNotContain(SYNTHETIC_VALUES);
            encodedInput = jsonEncode(encodedInput);
            encodedExpected = jsonEncode(encodedExpected);
        }
    }

    private static String toolResult(String stdout) throws Exception {

        return "{\"stdout\":" + JSON.writeValueAsString(stdout) + ",\"stderr\":\"\",\"interrupted\":false}";
    }

    // Words of a realistic configuration name that hold no credential word, cut to length.
    private static final String NAME_WORDS =
            "SPRING_CLOUD_CONFIG_SERVER_GIT_REPOS_MIRROR_PRIMARY_CLUSTER_REGION_EAST_REPLICA_POOL_DATA_SOURCE_"
                    + "PROPERTIES_HIKARI_CONNECTION_SETTINGS_SERVICE_ACCOUNT_DEFAULT_PROFILE_";

    // A name of exactly this many characters that ends with the credential word.
    private static String longName(int length, String word) {

        return NAME_WORDS.substring(0, length - word.length()) + word;
    }

    /**
     * A credential name may hold any number of characters before its credential word, up to 256:
     * each form keeps the name and loses the value, raw and JSON-encoded, inside a tool result, as a
     * structured member, and on the export and summary paths.
     */
    @ParameterizedTest(name = "{0}-character names")
    @ValueSource(ints = {60, 90, 120})
    void removesTheValuesOfLongCredentialNamesInEveryForm(int length) throws Exception {
        String upper = longName(length, "_PASSWORD");
        String lower = longName(length, "_token").toLowerCase(Locale.ROOT);
        String flag =
                "--" + longName(length - 2, "-secret").toLowerCase(Locale.ROOT).replace('_', '-');
        String display = String.join(
                "\n",
                "\tenvironment = {",
                "\t\t" + upper + " => F4KE7launchctl two words",
                "\t\t" + lower + " => F4KE7lowercase",
                "\t\tPATH => /usr/bin:/bin",
                "  \"" + upper + "\" => \"F4KE7plutil\"",
                "    " + upper + " = F4KE7buddy tail",
                upper + "=F4KE7env",
                "{\"" + upper + "\": \"F4KE7json\", \"port\": 5432}",
                "java -jar app.jar " + flag + " F4KE7flag --port 8766");
        String expected = String.join(
                "\n",
                "\tenvironment = {",
                "\t\t" + upper + " => [REDACTED]",
                "\t\t" + lower + " => [REDACTED]",
                "\t\tPATH => /usr/bin:/bin",
                "  \"" + upper + "\" => \"[REDACTED]\"",
                "    " + upper + " = [REDACTED]",
                upper + "=[REDACTED]",
                "{\"" + upper + "\": \"[REDACTED]\", \"port\": 5432}",
                "java -jar app.jar " + flag + " [REDACTED] --port 8766");
        assertThat(upper).hasSize(length);
        assertThat(flag).hasSize(length);
        String encodedInput = display;
        String encodedExpected = expected;
        for (int level = 0; level <= 4; level++) {
            assertThat(redactor.redact(encodedInput))
                    .as("JSON-encoded %d times", level)
                    .isEqualTo(encodedExpected);
            assertThat(redactor.redactForExport(encodedInput))
                    .doesNotContain("F4KE7")
                    .contains(upper);
            assertThat(redactor.redactForSummary(encodedInput)).isEqualTo(encodedExpected);
            if (level < 4) {
                assertThat(redactor.redact(toolResult(encodedInput)))
                        .as("in a tool result, JSON-encoded %d more times", level)
                        .isEqualTo(toolResult(encodedExpected));
            }
            encodedInput = jsonEncode(encodedInput);
            encodedExpected = jsonEncode(encodedExpected);
        }
        assertThat(redactor.redactDeep(Map.of("stdout", display, upper, "F4KE7member")))
                .isEqualTo(Map.of("stdout", expected, upper, "[REDACTED]"));
    }

    /**
     * Help output, manual pages and prose name credential flags without giving them an argument: a
     * description column, a description on the deeper-indented lines after a flag, a usage synopsis
     * and an English word after the flag all stay.
     */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "      --with-token            Read token from standard input",
                "  -p, --password string   Password or Personal Access Token (PAT), or \"-\"\n"
                        + "                          to read from stdin",
                "  --client-secret              Prompt for OAuth client secret (or set\n"
                        + "                               MCP_CLIENT_SECRET env var)",
                "       --ask-password              prompt for passwords",
                "      --hide-secret                                hide Kubernetes Secrets when also using the"
                        + " --dry-run flag",
                "      --kube-token string               bearer token used for authentication",
                "      --secret stringArray            Secret to expose to the build",
                "      --xml-raw-token                     enables using RawToken method instead Token.",
                "     --change-passphrase\tchange a passphrase",
                "  --api-key API_KEY     OpenAI API key",
                "  --password\n    Password for authentication. Can be provided via command line when",
                "  --connect-expired-password \n                      Notify the server that this client is prepared",
                "       --with-secret\n              Include info about the presence of a secret key in listings.",
                "  --openai-api-key OPENAI_API_KEY\n                        OpenAI API key",
                "usage: judge [-h] [--api-key API_KEY] [--port PORT]",
                "    --password -p          : The password used to log into a container registry.",
                "Use the --token flag to authenticate.",
                "The --api-key option is deprecated.",
                "Add --api-key support to the CLI",
                "Documented --auth-token usage in README",
                "Wired the --api-key flag into the CLI",
                "--hide-secret flag. Please carefully consider how and when these flags are used.",
                "    12→  --password\n    13→      Password for authentication.",
                "/fixture/docs/cli.md:12:  --password\n/fixture/docs/cli.md-13-      Password for authentication.",
                "  --token        Auth token (default: read from ~/.config/app/token)",
                "  --password     Prompt for the password interactively",
                "  --api-key      API key for the service",
                "      --token TOKEN           GitHub token",
                "  --secret NAME  Name of the secret to mount",
                "Document the --token flag",
                "Pass the --api-key option, which overrides the environment.",
                "Avoid the --password flag; it leaks into the shell history."
            })
    void keepsCredentialFlagsThatHelpAndProseOnlyName(String text) throws Exception {
        assertThat(redactor.redact(text)).isEqualTo(text);
        assertThat(redactor.redactForSummary(text)).isEqualTo(text);
        assertThat(redactor.redact(toolResult(text))).isEqualTo(toolResult(text));
        assertThat(redactor.redactDeep(Map.of("stdout", List.of(text)))).isEqualTo(Map.of("stdout", List.of(text)));
    }

    /** Text that resembles help or prose but passes a credential flag its argument still loses it. */
    static Stream<Arguments> credentialFlagArgumentsBesideHelpLikeText() {

        return Stream.of(
                Arguments.of("mycli --password secret --port 1", "mycli --password [REDACTED] --port 1"),
                Arguments.of("mycli --password letmein", "mycli --password [REDACTED]"),
                Arguments.of("mycli --api-key LETMEIN", "mycli --api-key [REDACTED]"),
                Arguments.of(
                        "docker run \\\n  --api-key     F4KE7aligned \\\n  --port        8766",
                        "docker run \\\n  --api-key     [REDACTED] \\\n  --port        8766"),
                Arguments.of("--api-key F4KE7argsFile\n--port 8766", "--api-key [REDACTED]\n--port 8766"),
                Arguments.of(
                        "  -p, --password F4KE7short   # set for the demo",
                        "  -p, --password [REDACTED]   # set for the demo"),
                Arguments.of("  --api-key\tF4KE7tab", "  --api-key\t[REDACTED]"),
                Arguments.of("  --api-key  F4KE7gap", "  --api-key  [REDACTED]"),
                Arguments.of("\t\t--api-key\n\t\tF4KE7 launchctl two words", "\t\t--api-key\n\t\t[REDACTED]"),
                Arguments.of("24:\t\t--api-key\n25-\t\tF4KE7 grep context", "24:\t\t--api-key\n[REDACTED]"),
                Arguments.of(
                        "    ProgramArguments = Array {\n        --db-password\n        F4KE7 buddy words\n    }",
                        "    ProgramArguments = Array {\n        --db-password\n        [REDACTED]\n    }"),
                // An aligned argument is not a help description: it holds digits or symbols, a '#'
                // comment or a parenthesized note follows it, or a continuation ends its line.
                Arguments.of(
                        "exec app \\\n  --verbose \\\n  --api-key   F4KE7s4   # prod key\n",
                        "exec app \\\n  --verbose \\\n  --api-key   [REDACTED]   # prod key\n"),
                Arguments.of(
                        "Effective flags:\n  --api-key        F4KE7u2    (from env)\n"
                                + "  --db-password    hunter8   (from file)\n",
                        "Effective flags:\n  --api-key        [REDACTED]    (from env)\n"
                                + "  --db-password    [REDACTED]   (from file)\n"),
                Arguments.of("  --db-password    letmein   (from file)", "  --db-password    [REDACTED]   (from file)"),
                Arguments.of("--password   letmein   # demo", "--password   [REDACTED]   # demo"),
                Arguments.of(
                        "docker run `\n  --api-key   F4KE7s7 `\n  myimage\n",
                        "docker run `\n  --api-key   [REDACTED] `\n  myimage\n"),
                Arguments.of(
                        "app.exe ^\n  --password   letmein ^\n  --port 1\n",
                        "app.exe ^\n  --password   [REDACTED] ^\n  --port 1\n"),
                Arguments.of("--api-key\tF4KE7t1\t# staging\n", "--api-key\t[REDACTED]\t# staging\n"),
                Arguments.of(
                        "flag\tvalue\tenv\n--api-key\tF4KE7u3\tprod\n--db-password\thunter9\tstaging\n",
                        "flag\tvalue\tenv\n--api-key\t[REDACTED]\tprod\n--db-password\t[REDACTED]\tstaging\n"),
                Arguments.of(
                        "1. --api-key   F4KE7t8 is the staging key\n",
                        "1. --api-key   [REDACTED] is the staging key\n"),
                Arguments.of(
                        "#   --api-key  F4KE7t9 old key, rotated\n", "#   --api-key  [REDACTED] old key, rotated\n"),
                Arguments.of(
                        "deploy.sh:12:  --api-key   F4KE7u4   # prod\n",
                        "deploy.sh:12:  --api-key   [REDACTED]   # prod\n"),
                Arguments.of("    12\t  --api-key   F4KE7u5   # prod\n", "    12\t  --api-key   [REDACTED]   # prod\n"),
                Arguments.of("> --api-key   F4KE7u6 worked for me\n", "> --api-key   [REDACTED] worked for me\n"),
                Arguments.of(
                        "exec app \\\n  --api-key F4KE7S6UPPER  # prod key\n",
                        "exec app \\\n  --api-key [REDACTED]  # prod key\n"),
                Arguments.of("  --api-key F4KE7UPPER   staging key", "  --api-key [REDACTED]   staging key"),
                // A deeper-indented next line is a description only when it reads as one.
                Arguments.of(
                        "  --api-key F4KE7U7UPPER\n      && echo ok\n", "  --api-key [REDACTED]\n      && echo ok\n"),
                Arguments.of(
                        "\targuments = {\n\t\t--api-key\n\t\t\tF4KE7w2\n\t}\n",
                        "\targuments = {\n\t\t--api-key\n\t\t\t[REDACTED]\n\t}\n"),
                Arguments.of(
                        "    ProgramArguments = Array {\n        --api-key\n          F4KE7w3\n    }\n",
                        "    ProgramArguments = Array {\n        --api-key\n          [REDACTED]\n    }\n"),
                Arguments.of("--api-key\n  F4KE7t3\n", "--api-key\n  [REDACTED]\n"),
                Arguments.of("--password\n    letmein\n", "--password\n    [REDACTED]\n"),
                // A bracket is a usage synopsis only around a metavar: argument slices keep losing it.
                Arguments.of("args=[--api-key F4KE7r2 --port 1]", "args=[--api-key [REDACTED] --port 1]"),
                Arguments.of("starting with [--api-key F4KE7r3]", "starting with [--api-key [REDACTED]]"),
                Arguments.of("[--api-key F4KE7v1]\n", "[--api-key [REDACTED]]\n"),
                Arguments.of("[--api-key F4KE7SECRET9]", "[--api-key [REDACTED]]"),
                // A word from prose's list is prose only standing alone, never as the head of an argument.
                Arguments.of("app --password not-a-secret --verbose", "app --password [REDACTED] --verbose"),
                Arguments.of("app --token will.i.am --verbose", "app --token [REDACTED] --verbose"),
                Arguments.of("app --password read-only-77 --verbose", "app --password [REDACTED] --verbose"),
                Arguments.of("app --password be@ch2026 --verbose", "app --password [REDACTED] --verbose"),
                Arguments.of("app --password value@prod --verbose", "app --password [REDACTED] --verbose"),
                Arguments.of("app --password which=one --verbose", "app --password [REDACTED] --verbose"),
                Arguments.of("app --password flags! --verbose", "app --password [REDACTED] --verbose"),
                Arguments.of("app --password the-secret --verbose", "app --password [REDACTED] --verbose"),
                Arguments.of("app --password it's-me", "app --password [REDACTED]"));
    }

    @ParameterizedTest
    @MethodSource("credentialFlagArgumentsBesideHelpLikeText")
    void credentialFlagsKeepTakingRealArguments(String input, String expected) throws Exception {
        assertThat(redactor.redact(input)).isEqualTo(expected);
        assertThat(redactor.redact(toolResult(input))).isEqualTo(toolResult(expected));
    }

    /**
     * The summary backend is sent each stored piece through the built-in rules: a tool input that
     * ingestion already redacted reaches it unchanged, still valid JSON, never losing the quote that
     * closes a replaced value.
     */
    static Stream<Arguments> toolInputsRedactedAtIngest() {

        return Stream.of(
                Arguments.of("defaults write com.apple.screensaver askForPassword -int F4KE7lock"),
                Arguments.of("node server.js --api-key F4KE7flag"),
                Arguments.of("export SBA_DB_PASSWORD=F4KE7env"),
                Arguments.of("mysql --user=root password=F4KE7pw"),
                Arguments.of("security add-generic-password -a fixture -s jev -w F4KE7sec"),
                Arguments.of("launchctl setenv SBA_DB_PASSWORD F4KE7setenv"),
                Arguments.of("/usr/bin/env SBA_X_TOKEN='F4KE7 quoted'"));
    }

    @ParameterizedTest
    @MethodSource("toolInputsRedactedAtIngest")
    void storedToolInputReachesTheSummaryBackendUnchanged(String command) throws Exception {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("command", command);
        input.put("description", "Run it");
        String stored = JSON.writeValueAsString(redactor.redactDeep(input));
        assertThat(stored).doesNotContain("F4KE7").contains("[REDACTED]").endsWith("\",\"description\":\"Run it\"}");
        assertThat(redactor.redactForSummary(stored)).isEqualTo(stored);
        assertThat(redactor.redact(stored)).isEqualTo(stored);
        assertThat(redactor.redactForSummary(jsonEncode(stored))).isEqualTo(jsonEncode(stored));
        assertThat(JSON.readTree(redactor.redactForSummary(stored))
                        .path("command")
                        .asText())
                .contains("[REDACTED]")
                .startsWith(command.substring(0, 6));
    }

    /**
     * Ingestion redacts each string of a tool input or output on its own and stores the whole as
     * JSON. The summary path reads that JSON string by string too, so a string that ends with a name
     * and separator, or with a value ingestion replaced, never runs on into the JSON after it, and a
     * clipped string is not clipped again: the backend receives the stored, valid JSON.
     */
    static Stream<Arguments> storedToolJson() {

        return Stream.of(
                Arguments.of("Grep for a name and colon", object("pattern", "password:", "path", "config")),
                Arguments.of("Grep for a name and equals sign", object("pattern", "api_key=", "glob", "*.env")),
                Arguments.of("Grep for a spaced assignment", object("pattern", "token =", "path", "src")),
                Arguments.of(
                        "Edit of a class name and colon",
                        object(
                                "file_path",
                                "/fixture/app/queue.py",
                                "old_string",
                                "class TokenQueue:",
                                "new_string",
                                "class TokenQueue(Queue):",
                                "replace_all",
                                false)),
                Arguments.of(
                        "Bash grep whose quoted name ingestion read a value after",
                        object("command", "grep -rn \"token:\" config/app.yml", "description", "Find token keys")),
                Arguments.of(
                        "Bash output that ends with a name and colon",
                        object(
                                "stdout",
                                "config/app.yml:3:  token:\nconfig/app.yml:4:  password:",
                                "stderr",
                                "",
                                "interrupted",
                                false)),
                Arguments.of(
                        "Write of a file longer than the scan budget",
                        object(
                                "file_path",
                                "/fixture/docs/notes.md",
                                "content",
                                "- Note: the release checklist covers build, smoke test and rollback steps.\n"
                                        .repeat(800))),
                Arguments.of(
                        "Codex command whose credential ingestion replaced",
                        object("command", List.of("bash", "-lc", "mycli --password F4KE7argv --port 1"))));
    }

    private static Map<String, Object> object(Object... namesAndValues) {
        Map<String, Object> object = new LinkedHashMap<>();
        for (int index = 0; index < namesAndValues.length; index += 2) {
            object.put((String) namesAndValues[index], namesAndValues[index + 1]);
        }

        return object;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("storedToolJson")
    void storedToolJsonReachesTheSummaryBackendAsStored(String tool, Map<String, Object> input) throws Exception {
        String stored = JSON.writeValueAsString(redactor.redactDeep(input));

        String sent = redactor.redactJsonForSummary(stored);

        assertThat(sent).isEqualTo(stored).doesNotContain("F4KE7");
        assertThat(JSON.readTree(sent).size()).isEqualTo(input.size());
    }

    /**
     * Stored JSON can predate a rule, or hold what ingestion reads in one string only: the summary
     * path redacts each string, a credential member's whole value, a flag's argument in the next
     * element of a list and a private key whose lines are separate elements, and keeps valid JSON.
     */
    @Test
    void summaryRedactsAStoredToolJsonThatHoldsACredential() throws Exception {
        String stored = "{\"command\":\"mycli --api-key F4KE7inline --port 1\","
                + "\"argv\":[\"mycli\",\"--token\",\"F4KE7element\",\"--port\",\"1\"],"
                + "\"env\":{\"SBA_DB_PASSWORD\":\"F4KE7member\",\"PATH\":\"/usr/bin\"},"
                + "\"structuredPatch\":[{\"oldStart\":1,\"lines\":[\" port: 8766\","
                + "\"+-----BEGIN RSA PRIVATE KEY-----\",\"+MIIF4KE7keyBody\",\"+F4KE7keyBody2\","
                + "\"+-----END RSA PRIVATE KEY-----\",\"+name: ci\"]}]}";

        String sent = redactor.redactJsonForSummary(stored);

        assertThat(sent).doesNotContain("F4KE7");
        var tree = JSON.readTree(sent);
        assertThat(tree.path("command").asText()).isEqualTo("mycli --api-key [REDACTED] --port 1");
        assertThat(tree.path("argv").toString()).isEqualTo("[\"mycli\",\"--token\",\"[REDACTED]\",\"--port\",\"1\"]");
        assertThat(tree.path("env").path("SBA_DB_PASSWORD").asText()).isEqualTo("[REDACTED]");
        assertThat(tree.path("env").path("PATH").asText()).isEqualTo("/usr/bin");
        assertThat(tree.path("structuredPatch").get(0).path("lines").toString())
                .isEqualTo(
                        "[\" port: 8766\",\"+[REDACTED]\",\"[REDACTED]\",\"[REDACTED]\",\"[REDACTED]\",\"+name: ci\"]");
        assertThat(redactor.redactJsonForSummary("mycli --api-key F4KE7text"))
                .as("text that is not JSON is redacted whole")
                .isEqualTo("mycli --api-key [REDACTED]");
        assertThat(redactor.redactJsonForSummary("{\"a\":1} --api-key F4KE7trailing"))
                .doesNotContain("F4KE7");
        assertThat(redactor.redactJsonForSummary(
                        "[\"-----BEGIN PRIVATE KEY-----\",42,\"--api-key\",\"F4KE7afterNumber\",\"--port\",\"1\"]"))
                .as("a key block never spans a number, and a flag after an open block keeps losing its argument")
                .isEqualTo("[\"-----BEGIN PRIVATE KEY-----\",42,\"--api-key\",\"[REDACTED]\",\"--port\",\"1\"]");
    }

    @Test
    void storedToolJsonWithManyOpenPrivateKeyLinesHasBoundedScanWork() throws Exception {
        List<String> lines = new ArrayList<>();
        for (int index = 0; lines.size() < 60_000; index++) {
            lines.add(index % 2 == 0 ? "+-----BEGIN RSA PRIVATE KEY-----" : "+--api-key");
        }
        String stored = JSON.writeValueAsString(Map.of("structuredPatch", List.of(Map.of("lines", lines))));

        assertTimeoutPreemptively(
                Duration.ofSeconds(4),
                () -> assertThat(redactor.redactJsonForSummary(stored)).isEqualTo(stored));
    }

    @Test
    void summaryRedactionKeepsTheClipMarkerOfAStoredText() {
        // Clipped at 50,000 characters, the replacement shortens the text, and the marker then ends
        // past the scan budget.
        String stored = redactor.redact("y".repeat(49_975) + " password=" + "F4KE".repeat(10));
        assertThat(stored).endsWith("password=[REDACTED] …[truncated]").hasSizeGreaterThan(50_000);
        assertThat(redactor.redactForSummary(stored)).isEqualTo(stored);
        assertThat(redactor.redact(stored)).isEqualTo(stored);
        String longer = redactor.redact("z".repeat(70_000));
        assertThat(redactor.redactForSummary(longer)).isEqualTo(longer);
    }

    /**
     * A quoted value can hold its own quote character as a unicode escape, as JavaScript, Python
     * and System.Text.Json write it. Decoding that escape for the second scan must never end the
     * value early and store the rest of it.
     */
    static Stream<Arguments> quotedValuesHoldingUnicodeEscapedQuotes() {

        return Stream.of(
                Arguments.of(
                        "JavaScript string with an escaped apostrophe",
                        "const c = {password: 'don\\u0027t F4KE7tell anyone', port: 1};",
                        "const c = {password: '[REDACTED]', port: 1};"),
                Arguments.of(
                        "Python string with an escaped apostrophe",
                        "DB = {'password': 'it\\u0027s F4KE7a long passphrase', 'port': 5432}",
                        "DB = {'password': '[REDACTED]', 'port': 5432}"),
                Arguments.of(
                        "System.Text.Json value with an escaped quote",
                        "{\"ConnectionStrings\":{\"DbPassword\":\"pa\\u0022ss F4KE7word here\"},\"Port\":5432}",
                        "{\"ConnectionStrings\":{\"DbPassword\":\"[REDACTED]\"},\"Port\":5432}"),
                Arguments.of(
                        "JSON member with an escaped quote",
                        "\"client_secret\": \"x\\u0022y F4KE7z\", \"note\": \"kept\"",
                        "\"client_secret\": \"[REDACTED]\", \"note\": \"kept\""),
                Arguments.of(
                        "YAML value with an escaped apostrophe",
                        "password: 'F4KE7swap\\u0027 swap tail'\nport: 5432",
                        "password: '[REDACTED]'\nport: 5432"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("quotedValuesHoldingUnicodeEscapedQuotes")
    void keepsAQuotedValueWholeWhenItHoldsAUnicodeEscapedQuote(String source, String input, String expected)
            throws Exception {
        assertThat(redactor.redact(input)).isEqualTo(expected);
        assertThat(redactor.redact(toolResult(input))).isEqualTo(toolResult(expected));
        assertThat(redactor.redactDeep(Map.of("content", List.of(input))))
                .isEqualTo(Map.of("content", List.of(expected)));
        assertThat(redactor.redactForExport(input)).doesNotContain("F4KE7");
        assertThat(redactor.redactForExport(toolResult(input))).doesNotContain("F4KE7");
    }

    static Stream<Arguments> encoderEscapes() {

        return Stream.of(
                Arguments.of(
                        "slash-escaped closing tags",
                        "<key>SBA_JUDGE_API_KEY<\\/key>\\n<string>F4KE7 php<\\/string>",
                        "<key>SBA_JUDGE_API_KEY<\\/key>\\n<string>[REDACTED]<\\/string>"),
                // A credential found only after decoding HTML-safe escapes keeps the decoded characters;
                // one the escaped text already shows keeps its escapes.
                Arguments.of(
                        "HTML-safe escapes in plist XML",
                        "\\u003ckey\\u003eSBA_JUDGE_API_KEY\\u003c/key\\u003e\\n\\u003cstring\\u003eF4KE7 go\\u003c/string\\u003e",
                        "<key>SBA_JUDGE_API_KEY</key>\\n<string>[REDACTED]</string>"),
                Arguments.of(
                        "unicode-escaped quotes in plutil -p output",
                        "\\u0022SBA_JUDGE_API_KEY\\u0022 => \\u0022F4KE7 quoted\\u0022",
                        "\\u0022SBA_JUDGE_API_KEY\\u0022 => \\u0022[REDACTED]\\u0022"),
                Arguments.of(
                        "HTML-safe escape inside =>",
                        "\\\"SBA_JUDGE_API_KEY\\\" =\\u003e \\\"F4KE7 arrow\\\"",
                        "\\\"SBA_JUDGE_API_KEY\\\" =\\u003e \\\"[REDACTED]\\\""),
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
    void privateKeyBlocksAreReplacedFromEachBeginLineThroughTheNextEndLine() {
        String block = "-----BEGIN RSA PRIVATE KEY-----\nF4KE7material\n-----END RSA PRIVATE KEY-----";
        assertThat(redactor.redact("a " + block + " b " + block + " c")).isEqualTo("a [REDACTED] b [REDACTED] c");
        // A second BEGIN line before the END line belongs to the first block.
        assertThat(redactor.redact("-----BEGIN PRIVATE KEY-----\nF4KE7a\n-----begin private key-----\nF4KE7b\n"
                        + "-----END PRIVATE KEY----- tail"))
                .isEqualTo("[REDACTED] tail");
        // A BEGIN line that no END line follows starts no block, and neither does a later one.
        assertThat(redactor.redact(block + " mid -----BEGIN PRIVATE KEY-----\nunfinished -----BEGIN PRIVATE KEY-----"))
                .isEqualTo("[REDACTED] mid -----BEGIN PRIVATE KEY-----\nunfinished -----BEGIN PRIVATE KEY-----");
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
        // A changed ProgramArguments value: both sides of the diff go, and the chain stops at the next flag.
        assertThat(redactor.redactDeep(List.of(
                        "     <string>--api-key</string>",
                        "-    <string>F4KE7 old</string>",
                        "+    <string>F4KE7 new</string>",
                        "     <string>--port</string>",
                        "     <string>8766</string>")))
                .isEqualTo(List.of(
                        "     <string>--api-key</string>",
                        "-    <string>[REDACTED]</string>",
                        "+    <string>[REDACTED]</string>",
                        "     <string>--port</string>",
                        "     <string>8766</string>"));
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
                    "\\".repeat(15) + "\"token" + "\\".repeat(14) + "\" => " + "\\".repeat(16) + "\" "),
            Arguments.of(
                    "names after long line prefixes",
                    "\n" + "a".repeat(255) + " judgeToken => x \n" + "a-".repeat(100) + "db_token => y\n"
                            + "x.".repeat(70) + "password => z \\n" + "1:".repeat(120) + "\\tapi_token => w "),
            Arguments.of(
                    "lambdas and plutil values that start their lines",
                    "\n  token => " + "tokens".repeat(40) + "\n  \"SBA_X_TOKEN\" => \"" + "x\\\"".repeat(60) + "\n"
                            + "  \\\"SBA_Y_TOKEN\\\" => \\\"" + "y\\\\\\\"".repeat(40) + "\\n"),
            Arguments.of(
                    "launchctl values followed by unindented lines",
                    "\t\tSBA_X_TOKEN => v\n" + ("SBA_Y_TOKEN => w " + "x".repeat(60) + "\n").repeat(70) + "\n"),
            Arguments.of(
                    "credential flags without arguments",
                    "--api-key\n--api-key \"--api-key\"" + " ,".repeat(127) + "x \\\"--db-password\\\""
                            + "\\n".repeat(120) + "\\\\\" --token -x "),
            Arguments.of(
                    "write commands without credential names",
                    "setenv " + "A".repeat(130) + " x Set :" + "a:".repeat(20) + "b".repeat(130) + " x -replace "
                            + "k.".repeat(300) + " -string x defaults write " + "d".repeat(300) + " Key x "
                            + "add-generic-password " + "y".repeat(520) + " -w\n"),
            Arguments.of(
                    "PlistBuddy writes of credential names on JSON-escaped lines",
                    "set :token v\\nAdd :a:b:password string v\\r\\nSet :SBA_X_TOKEN v\\\\n"
                            + "setenv SBA_TOKEN v\\ndefaults write d SBA_TOKEN v\\n"),
            Arguments.of(
                    "long flag-shaped words", " --api-key" + "-a".repeat(2_000) + " value --token" + ".x".repeat(900)),
            Arguments.of(
                    "provider keys glued to short hexadecimal runs",
                    "Hapikey_" + "0123456789abcdef" + "f".repeat(15) + "g apikey_apikey_" + "0".repeat(31) + "x "),
            Arguments.of(
                    "names after whitespace runs and other text",
                    "\n" + " ".repeat(200) + "x SBA_A_TOKEN => v SBA_B_TOKEN => w\n" + "\t".repeat(220)
                            + "x token => token token => token\n" + " \t".repeat(90) + "x SBA_X_PASSWORD = v\n"
                            + " ".repeat(120) + ">" + " ".repeat(120) + "x judgeToken => judgeToken\n"),
            Arguments.of(
                    "credential flags after whitespace runs and other text",
                    "\n" + " ".repeat(250) + "x --api-key\n" + " ".repeat(160) + "y --api-key\"\n" + " ".repeat(240)
                            + "z --api-key\\\n" + "\\n" + "\\t".repeat(120) + "x --token\\n"),
            Arguments.of(
                    "credential flags in help layouts",
                    "  --api-key" + " ".repeat(300) + "x\n  -p, --password string" + " ".repeat(200) + "two words\n"
                            + "  --password\n" + " ".repeat(250) + "Password for it\n"),
            Arguments.of(
                    "long names without a separator",
                    "SBA_" + "SEGMENT_".repeat(30) + "PASSWORD v " + "A".repeat(300) + "=v "
                            + "tokenpassphrase".repeat(30) + " \\\"SBA_" + "A_".repeat(60) + "PASSWORD\\\" x \\tSBA_"
                            + "SEG_".repeat(30) + "TOKEN x --" + "a-".repeat(60) + "password" + "x v --"
                            + "a-".repeat(200) + "token v "),
            Arguments.of(
                    "private key BEGIN lines without an END line",
                    "-----BEGIN RSA PRIVATE KEY-----\n" + "x".repeat(20) + "\n-----END RSA PUBLIC KEY-----\n"),
            // Every "--" after a "." starts a run of flag characters too long to be a flag.
            Arguments.of("flag-shaped runs after dots", ".--"),
            // One escaped markup character in each unit makes every scalar take the decoded second
            // scan, so each of these near misses is scanned twice.
            Arguments.of(
                    "flag-shaped runs after dots, with an escaped markup character", "\\u003c" + ".--".repeat(333)),
            Arguments.of(
                    "security commands without a password option, with an escaped markup character",
                    "\\u003c" + ".add-generic-password".repeat(48)),
            Arguments.of(
                    "names on a line after a long backslash run, with an escaped quote",
                    "\\u0022" + "\\".repeat(20_000) + "n" + "token => ".repeat(28) + "\n"));

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
