package dev.nathan.sbaagentic.recording;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import java.util.List;

/** A fact with provenance and optional typed links to prior captures. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CaptureEvidenceRequest(
        @NotBlank String source,
        @NotBlank String clientSessionId,
        String repo,
        @NotBlank String claim,
        String excerpt,
        @NotBlank String sourceRef,
        String outputDigest,
        String observedAt,
        String capturedBy,
        List<String> supports,
        List<String> refutes,
        String notes,
        String project,
        List<LaneListing> alsoIn) {}
