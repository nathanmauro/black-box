package dev.nathan.sbaagentic.recording;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nathan.sbaagentic.recording.internal.application.RedactionService;
import java.time.Duration;
import java.util.ArrayList;
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
                        java -jar app.jar --spring-datasource-hikari-data-source-properties-password [REDACTED] --port 8766"""));
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
                    "long names without a separator",
                    "SBA_" + "SEGMENT_".repeat(30) + "PASSWORD v " + "A".repeat(300) + "=v "
                            + "tokenpassphrase".repeat(30) + " \\\"SBA_" + "A_".repeat(60) + "PASSWORD\\\" x \\tSBA_"
                            + "SEG_".repeat(30) + "TOKEN x --" + "a-".repeat(60) + "password" + "x v --"
                            + "a-".repeat(200) + "token v "),
            Arguments.of(
                    "private key BEGIN lines without an END line",
                    "-----BEGIN RSA PRIVATE KEY-----\n" + "x".repeat(20) + "\n-----END RSA PUBLIC KEY-----\n"));

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
