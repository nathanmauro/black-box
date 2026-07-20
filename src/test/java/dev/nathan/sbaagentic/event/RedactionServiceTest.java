package dev.nathan.sbaagentic.recording;

import dev.nathan.sbaagentic.recording.internal.application.RedactionService;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import dev.nathan.sbaagentic.config.SbaProperties;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class RedactionServiceTest {

    @Test
    void redactsPrivateKeyBlocks() {
        RedactionService redactionService = redactionService();

        String redacted = redactionService.redact("""
                before
                -----BEGIN RSA PRIVATE KEY-----
                private material
                -----END RSA PRIVATE KEY-----
                after
                """);

        assertThat(redacted).isEqualTo("""
                before
                [REDACTED]
                after
                """);
    }

    @Test
    void redactsSecretAssignmentsAndPreservesKeyName() {
        RedactionService redactionService = redactionService();

        assertThat(redactionService.redact("export AWS_SECRET_ACCESS_KEY=wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"))
                .isEqualTo("export AWS_SECRET_ACCESS_KEY=[REDACTED]");
    }

    @Test
    void redactsPrefixedAndHyphenatedKeyNames() {
        RedactionService redactionService = redactionService();

        assertThat(redactionService.redact("MY_SECRET_KEY=abcd1234efgh"))
                .isEqualTo("MY_SECRET_KEY=[REDACTED]");
        assertThat(redactionService.redact("DB-ACCESS-TOKEN: hunter22hunter"))
                .isEqualTo("DB-ACCESS-TOKEN: [REDACTED]");
    }

    @Test
    void staysLinearOnKeywordDenseInput() {
        RedactionService redactionService = redactionService();
        // ~100k chars of bare secret keywords with no assignment value: the old greedy
        // flanked pattern backtracked quadratically here (seconds of CPU); the bounded
        // shape plus the scan clip must stay comfortably under the timeout.
        String hostile = "token ".repeat(17_000);

        String redacted = assertTimeoutPreemptively(Duration.ofSeconds(2),
                () -> redactionService.redact(hostile));

        assertThat(redacted).contains("token");
    }

    @Test
    void clipsOversizedInputBeforeScanning() {
        RedactionService redactionService = redactionService();
        String early = "password=supersecret123 ";
        String oversized = early + "x".repeat(70_000);

        String redacted = redactionService.redact(oversized);

        assertThat(redacted).startsWith("password=[REDACTED]");
        assertThat(redacted).endsWith("…[truncated]");
        assertThat(redacted.length()).isLessThan(51_000);
    }

    @Test
    void redactsAuthorizationBearerTokens() {
        RedactionService redactionService = redactionService();

        assertThat(redactionService.redact("Authorization: Bearer sk-proj-abc123def456ghi789jkl"))
                .isEqualTo("Authorization: Bearer [REDACTED]");
    }

    @Test
    @SuppressWarnings("unchecked")
    void redactsDeepStringValuesAndPreservesStructure() {
        RedactionService redactionService = redactionService();
        Map<String, Object> input = Map.of(
                "stdout", "ghp_abcdefghijklmnopqrstuvwxyz0123456789AB",
                "nested", Map.of("list", List.of("xoxb-1234567890-abcdefghij")));

        Object redacted = redactionService.redactDeep(input);

        assertThat(redacted).isInstanceOf(Map.class);
        Map<String, Object> redactedMap = (Map<String, Object>) redacted;
        assertThat(redactedMap).containsEntry("stdout", "[REDACTED]");
        assertThat(redactedMap.get("nested")).isInstanceOf(Map.class);
        assertThat((Map<String, Object>) redactedMap.get("nested"))
                .containsEntry("list", List.of("[REDACTED]"));
    }

    @Test
    void disabledRedactionPassesInputsThrough() {
        SbaProperties properties = new SbaProperties();
        properties.getIngestion().setRedactEnabled(false);
        RedactionService redactionService = new RedactionService(properties);
        String text = "ghp_abcdefghijklmnopqrstuvwxyz0123456789AB";
        Map<String, Object> input = Map.of("stdout", text);

        assertThat(redactionService.redact(text)).isSameAs(text);
        assertThat(redactionService.redactDeep(input)).isSameAs(input);
    }

    @Test
    void leavesBenignTextUntouched() {
        RedactionService redactionService = redactionService();

        assertThat(redactionService.redact("the token bucket algorithm"))
                .isEqualTo("the token bucket algorithm");
        assertThat(redactionService.redact("password requirements documented"))
                .isEqualTo("password requirements documented");
    }

    @Test
    void customPatternsReplaceDefaultPatterns() {
        SbaProperties properties = new SbaProperties();
        properties.getIngestion().setRedactPatterns(List.of("INTERNAL-[0-9]{4}"));
        RedactionService redactionService = new RedactionService(properties);

        assertThat(redactionService.redact("INTERNAL-1234 ghp_abcdefghijklmnopqrstuvwxyz0123456789AB"))
                .isEqualTo("[REDACTED] ghp_abcdefghijklmnopqrstuvwxyz0123456789AB");
    }

    private static RedactionService redactionService() {
        return new RedactionService(new SbaProperties());
    }
}
