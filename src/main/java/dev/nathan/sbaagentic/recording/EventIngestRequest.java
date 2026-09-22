package dev.nathan.sbaagentic.recording;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record EventIngestRequest(
        @NotBlank String source,
        @NotBlank String clientSessionId,
        String turnId,
        @NotBlank String eventType,
        String role,
        String text,
        String cwd,
        String toolName,
        Object toolInput,
        Object toolOutput,
        Map<String, Object> metadata,
        Instant observedAt) {}
