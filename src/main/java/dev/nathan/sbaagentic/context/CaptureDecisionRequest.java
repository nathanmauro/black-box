package dev.nathan.sbaagentic.context;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import jakarta.validation.constraints.NotBlank;

/**
 * A structured decision an agent commits into the recorder: what it chose, why, what else it
 * considered, how sure it is, and what it knowingly left unfinished. {@code repo} anchors the
 * decision so a later agent in the same working directory can recall it.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CaptureDecisionRequest(
        @NotBlank String source,
        @NotBlank String clientSessionId,
        String repo,
        @NotBlank String decision,
        String rationale,
        List<String> alternatives,
        Double confidence,
        List<String> openLoops) {
}
