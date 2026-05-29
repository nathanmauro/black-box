package dev.nathan.sbaagentic.context;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import jakarta.validation.constraints.NotBlank;

/**
 * A handoff an agent leaves for whoever picks the work up next — another agent, another tool, or a
 * future self. The open loops and next action are the load-bearing fields: they are what a fresh,
 * amnesiac agent recalls to avoid re-deciding what was already decided.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CaptureHandoffRequest(
        @NotBlank String source,
        @NotBlank String clientSessionId,
        String repo,
        String toAgent,
        @NotBlank String contextSummary,
        List<String> openLoops,
        String nextAction) {
}
