package dev.nathan.sbaagentic.recording;

import static org.assertj.core.api.Assertions.assertThat;

import dev.nathan.sbaagentic.recording.internal.application.RedactionService;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class StructuredRedactionTest {
    private static final String SECRET = "FAKE_CAPTURE_SECRET_123456789";
    private static final String PROVIDER_TOKEN = "ghp_abcdefghijklmnopqrstuvwxyz0123456789AB";
    private final RedactionService redactor = new RedactionService(new IngestionProperties());

    @ParameterizedTest
    @ValueSource(
            strings = {
                "api_key",
                "API-KEY",
                "api.key",
                "Api Key",
                "a_p_i_K_e_y",
                "clientSecret",
                "AWS_SECRET_ACCESS_KEY",
                "access_token",
                "TOKEN",
                "db-passwd",
                "Password",
                "Authorization",
                "credentials",
                "Private Key",
                "nested.private_key",
                "tokenCount"
            })
    void defaultRulesReplaceEntireSecretKeyValuesRegardlessOfShape(String key) {
        for (Object value : Arrays.asList(
                SECRET, "x", 1234, false, null, Map.of("readable", SECRET), List.of(SECRET, Map.of("nested", "x")))) {
            Map<String, Object> input = new LinkedHashMap<>();
            input.put(key, value);
            assertThat(redactor.redactDeep(input)).isEqualTo(Map.of(key, "[REDACTED]"));
            assertThat(input.get(key)).isSameAs(value);
        }
    }

    @Test
    void redactsNestedListsAndMapsWithoutChangingOrdinaryMetadataOrIdentity() {
        Map<String, Object> input = Map.of(
                "source",
                "codex",
                "clientSessionId",
                "fixture-session",
                "repo",
                "/fixture/redaction",
                "agentId",
                "fixture-agent",
                "turnId",
                "fixture-turn",
                "path",
                "/fixture/file.txt",
                "nested",
                List.of(
                        Map.of("password", SECRET, "count", 2),
                        List.of(Map.of("headers", Map.of("Authorization", SECRET), "ok", true))),
                "values",
                List.of("readable", 7, false));
        Map<String, Object> expected = new LinkedHashMap<>(input);
        expected.put(
                "nested",
                List.of(
                        Map.of("password", "[REDACTED]", "count", 2),
                        List.of(Map.of("headers", Map.of("Authorization", "[REDACTED]"), "ok", true))));

        assertThat(redactor.redactDeep(input)).isEqualTo(expected);
        assertThat(input.toString()).contains(SECRET);
    }

    @Test
    void scansSecretBearingMemberNamesAndKeepsTheirNonsecretValues() {
        Map<String, Object> input = Map.of(PROVIDER_TOKEN, "readable", "password=" + SECRET, "x");

        assertThat(redactor.redactDeep(input))
                .isEqualTo(Map.of("[REDACTED]", "readable", "password=[REDACTED]", "[REDACTED]"));
    }

    @Test
    void changedMemberNamesCannotOverwriteExistingBenignFieldsOrEachOther() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put(PROVIDER_TOKEN, "first");
        input.put("[REDACTED]", "existing");
        input.put("[REDACTED] (redacted key 1)", "also existing");
        input.put("ghp_ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789ab", "second");

        assertThat(redactor.redactDeep(input))
                .isEqualTo(Map.of(
                        "[REDACTED] (redacted key 2)", "first",
                        "[REDACTED]", "existing",
                        "[REDACTED] (redacted key 1)", "also existing",
                        "[REDACTED] (redacted key 3)", "second"));
    }

    @Test
    void customPatternsReplaceBuiltInKeyAndValueRules() {
        IngestionProperties properties = new IngestionProperties();
        properties.setRedactPatterns(List.of("INTERNAL-[0-9]{4}"));
        RedactionService custom = new RedactionService(properties);
        Map<String, Object> input = Map.of(
                "api_key",
                SECRET,
                "credentials",
                Map.of("password", "x"),
                PROVIDER_TOKEN,
                PROVIDER_TOKEN,
                "INTERNAL-1234",
                List.of("INTERNAL-9876"));

        assertThat(custom.redactDeep(input))
                .isEqualTo(Map.of(
                        "api_key",
                        SECRET,
                        "credentials",
                        Map.of("password", "x"),
                        PROVIDER_TOKEN,
                        PROVIDER_TOKEN,
                        "[REDACTED]",
                        List.of("[REDACTED]")));
    }

    @Test
    void disabledModeReturnsTheExactOriginalObjectIncludingSecretKeys() {
        IngestionProperties properties = new IngestionProperties();
        properties.setRedactEnabled(false);
        RedactionService disabled = new RedactionService(properties);
        Map<String, Object> input = Map.of("api_key", List.of(SECRET), PROVIDER_TOKEN, "x");

        assertThat(disabled.redactDeep(input)).isSameAs(input);
        assertThat(disabled.redactDeep(SECRET)).isSameAs(SECRET);
    }
}
