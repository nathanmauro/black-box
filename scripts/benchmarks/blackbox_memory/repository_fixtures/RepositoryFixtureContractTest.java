package dev.nathan.sbaagentic.recording;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import dev.nathan.sbaagentic.recording.internal.application.RedactionService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Trusted, provider-free grading for the pinned development fixture; never given to a worker. */
class RepositoryFixtureContractTest {
    private static final String FAKE_TOKEN = "ghp_abcdefghijklmnopqrstuvwxyz0123456789AB";
    private final RedactionService redactor = new RedactionService(new IngestionProperties());

    @Test
    void secretValuesAreReplaced() {
        assertEquals(
                Map.of("api_key", "[REDACTED]", "nested", List.of(Map.of("Authorization", "[REDACTED]"))),
                redactor.redactDeep(
                        Map.of("api_key", "fixture-value", "nested", List.of(Map.of("Authorization", false)))));
    }

    @Test
    void secretMemberNamesCannotLeak() {
        assertEquals(Map.of("[REDACTED]", "ordinary"), redactor.redactDeep(Map.of(FAKE_TOKEN, "ordinary")));
    }

    @Test
    void redactedMemberNamesDoNotCollide() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put(FAKE_TOKEN, "first");
        input.put("[REDACTED]", "existing");
        assertEquals(
                Map.of("[REDACTED] (redacted key 1)", "first", "[REDACTED]", "existing"), redactor.redactDeep(input));
    }

    @Test
    void ordinaryEvidenceIsPreserved() {
        Map<String, Object> input =
                Map.of("repo", "/fixture/repo", "turnId", "turn-1", "values", List.of(3, false, "plain"));
        assertEquals(input, redactor.redactDeep(input));
        assertEquals("turn-1", input.get("turnId"));
    }

    @Test
    void customPolicyStillOverridesDefaults() {
        IngestionProperties properties = new IngestionProperties();
        properties.setRedactPatterns(List.of("FIXTURE-[0-9]+"));
        assertEquals(
                Map.of("api_key", "keep", "ordinary", "[REDACTED]"),
                new RedactionService(properties).redactDeep(Map.of("api_key", "keep", "ordinary", "FIXTURE-123")));
    }

    @Test
    void disabledPolicyPreservesOriginal() {
        IngestionProperties properties = new IngestionProperties();
        properties.setRedactEnabled(false);
        Map<String, Object> input = Map.of("api_key", List.of("fixture-value"));
        assertSame(input, new RedactionService(properties).redactDeep(input));
    }
}
