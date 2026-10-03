package dev.nathan.sbaagentic.recording;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import dev.nathan.sbaagentic.recording.internal.application.RedactionService;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class QuotedCredentialRedactionTest {
    private final RedactionService redactor = new RedactionService(new IngestionProperties());

    static Stream<Arguments> assignments() {

        return Stream.of(
                Arguments.of("before password=\"FAKE SPACE CREDENTIAL\" after", "before password=\"[REDACTED]\" after"),
                Arguments.of("before password='FAKE SPACE CREDENTIAL' after", "before password='[REDACTED]' after"),
                Arguments.of(
                        "{\"api_key\":\"F4KE7\",\"note\":\"readable\"}",
                        "{\"api_key\":\"[REDACTED]\",\"note\":\"readable\"}"),
                Arguments.of("{'token': 'F4KE7', 'ok': true}", "{'token': '[REDACTED]', 'ok': true}"),
                Arguments.of("password=F4KE7 next=readable", "password=[REDACTED] next=readable"),
                Arguments.of("token=F4KE7,next=readable", "token=[REDACTED],next=readable"),
                Arguments.of("{\"password\":F4KE7}", "{\"password\":[REDACTED]}"),
                Arguments.of("password=\"\" after", "password=\"[REDACTED]\" after"),
                Arguments.of("password=\"FAKE \\\"QUOTED\\\" CREDENTIAL\" after", "password=\"[REDACTED]\" after"),
                Arguments.of("client_secret='FAKE \\'QUOTED\\' CREDENTIAL' after", "client_secret='[REDACTED]' after"),
                Arguments.of("password=\"FAKE CREDENTIAL\\\\\" after", "password=\"[REDACTED]\" after"),
                Arguments.of("Authorization: Bearer F4KE7 after", "Authorization: Bearer [REDACTED] after"),
                Arguments.of("MY_SECRET_KEY = 'F4KE7' after", "MY_SECRET_KEY = '[REDACTED]' after"),
                Arguments.of("password=\"FAKE unfinished credential", "password=\"[REDACTED]"),
                Arguments.of("password=\"FAKE CREDENTIAL\\\"", "password=\"[REDACTED]"),
                Arguments.of(
                        "password='FAKE } , = token=x CREDENTIAL' next=readable",
                        "password='[REDACTED]' next=readable"),
                Arguments.of(
                        "password='FAKE\nMULTILINE CREDENTIAL' next=readable", "password='[REDACTED]' next=readable"),
                Arguments.of("password=, token=F4KE7 after", "password=, token=[REDACTED] after"),
                Arguments.of("password=[REDACTED]F4KE7 after", "password=[REDACTED] after"),
                Arguments.of("token=[REDACTED].[REDACTED].F4KE7 after", "token=[REDACTED] after"),
                Arguments.of("token=prefix[REDACTED]F4KE7,next=readable", "token=[REDACTED],next=readable"),
                Arguments.of("token=[REDACTED][REDACTED]F4KE7]next", "token=[REDACTED]]next"));
    }

    @ParameterizedTest
    @MethodSource("assignments")
    void removesNamedCredentialsFromTextAndNestedStringLeaves(String input, String expected) {
        assertThat(redactor.redact(input)).isEqualTo(expected);
        assertThat(redactor.redactDeep(Map.of("stdout", List.of(input))))
                .isEqualTo(Map.of("stdout", List.of(expected)));
        assertThat(redactor.redactForExport(input)).doesNotContain("FAKE", "F4KE7");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "the token bucket algorithm",
                "password requirements documented",
                "note='readable text'",
                "api.key=F4KE7",
                "private_key=F4KE7"
            })
    void preservesBenignTextAndExistingAssignmentKeyClassification(String text) {
        assertThat(redactor.redact(text)).isEqualTo(text);
    }

    @ParameterizedTest
    @ValueSource(strings = {"password=\"FAKE unfinished credential", "tokenAA="})
    void completePrivateKeyBlocksAreRemovedBeforeParsingAssignmentsInsideThem(String assignmentLine) {
        String text = "before\n-----BEGIN PRIVATE KEY-----\nFAKE_PRIVATE_MATERIAL\n" + assignmentLine
                + "\n-----END PRIVATE KEY-----\nafter";
        assertThat(redactor.redact(text)).isEqualTo("before\n[REDACTED]\nafter");
        assertThat(redactor.redactForExport(text)).isEqualTo("before\n[REDACTED]\nafter");
    }

    @Test
    void redactedBareAndQuotedAssignmentsRemainStable() {
        String text = "password=[REDACTED] token='[REDACTED]' Authorization: Bearer [REDACTED]";
        assertThat(redactor.redact(text)).isEqualTo(text);
        assertThat(redactor.redactForExport(text)).isEqualTo(text);
    }

    @Test
    void customRulesReplaceDefaultAssignmentsAndDisabledIngestionPassesThrough() {
        String text = "password=\"FAKE SPACE CREDENTIAL\" INTERNAL-1234";
        var customProperties = new IngestionProperties();
        customProperties.setRedactPatterns(List.of("INTERNAL-[0-9]{4}"));
        var custom = new RedactionService(customProperties);
        assertThat(custom.redact(text)).isEqualTo("password=\"FAKE SPACE CREDENTIAL\" [REDACTED]");
        assertThat(custom.redactForExport(text)).isEqualTo("password=\"[REDACTED]\" INTERNAL-1234");
        var disabledProperties = new IngestionProperties();
        disabledProperties.setRedactEnabled(false);
        var disabled = new RedactionService(disabledProperties);
        assertThat(disabled.redact(text)).isSameAs(text);
        assertThat(disabled.redactForExport(text)).isEqualTo("password=\"[REDACTED]\" INTERNAL-1234");
    }

    @Test
    void clippedQuotedValuesDropTheUnscannedTailAndKeepTheMarker() {
        String text = "password=\"" + "FAKE CREDENTIAL with spaces ".repeat(4_000) + "\" tail";
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            assertThat(redactor.redact(text)).isEqualTo("password=\"[REDACTED] …[truncated]");
            assertThat(redactor.redactForExport(text)).isEqualTo("password=\"[REDACTED] …[truncated]");
        });
    }

    @Test
    void denseAssignmentsAndCredentialWordsHaveBoundedScanWork() {
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            String dense = redactor.redact("token=F4KE7 ".repeat(10_000));
            assertThat(dense).doesNotContain("F4KE7").endsWith(" …[truncated]");
            assertThat(redactor.redact("token ".repeat(17_000))).endsWith(" …[truncated]");
            assertThat(redactor.redactForExport("token ".repeat(17_000))).endsWith(" …[truncated]");
        });
    }
}
