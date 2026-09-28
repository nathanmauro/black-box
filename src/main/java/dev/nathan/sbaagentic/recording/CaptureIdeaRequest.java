package dev.nathan.sbaagentic.recording;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.util.List;

/**
 * An idea someone proposed that is not being acted on right now: a human's aside or an agent's
 * suggestion. Captures are append-only; a status change is a new capture with the same
 * {@code ideaKey}. See {@link Ideas} for the allowed {@code origin} and {@code status} values.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CaptureIdeaRequest(
        @NotBlank String source,
        @NotBlank String clientSessionId,
        String repo,
        @NotBlank String title,
        @NotBlank String oneLiner,
        @NotBlank String origin,
        String quote,
        String sourceRef,
        @Min(0) @Max(10) Integer legs,
        String status,
        List<String> connects,
        String resumeStep,
        String link,
        String notes,
        String ideaKey) {}
