package dev.nathan.sbaagentic.recording.internal.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import dev.nathan.sbaagentic.recording.EventIngestRequest;
import dev.nathan.sbaagentic.recording.IdempotentEventIngestRequest;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CaptureIdentityTest {

    @Test
    void digestV1KeepsItsFixedFieldAndSerializationContract() {
        var event = new EventIngestRequest(" Codex ", " client-42 ", null, "Observation", "assistant",
                "fixture text\nline two", "/repo", "shell",
                Map.of("flags", List.of("a", "b"), "command", "printf hi"), null,
                Map.of("z", List.of(3, 2, 1), "a", Map.of("second", "two", "first", "one")),
                Instant.parse("2026-09-19T12:34:56.123456789Z"));
        var identity = CaptureIdentity.from(new IdempotentEventIngestRequest(
                "5F067AD6-902F-4AE9-BC58-0FD1DE998C33", event));

        // Independently computed from UTF-8 JSON with sorted keys, compact separators, all 12
        // digestV1 fields (including explicit nulls), ISO-8601 Instant, and normalized namespace.
        assertThat(identity.requestHash()).isEqualTo("2ecca5dc8e4429967204f30b72d20d048cd072524197628481662f2c6506f3bb");
        assertThat(identity.source()).isEqualTo("codex");
        assertThat(identity.clientSessionId()).isEqualTo("client-42");
        assertThat(identity.captureId()).isEqualTo("5f067ad6-902f-4ae9-bc58-0fd1de998c33");
    }
}
