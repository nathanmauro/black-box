package dev.nathan.sbaagentic.recording;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Retry-capable envelope; the original event contract remains unchanged. */
public record IdempotentEventIngestRequest(
        @NotBlank String captureId, @NotNull @Valid EventIngestRequest event) {}
