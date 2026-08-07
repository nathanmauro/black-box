package dev.nathan.sbaagentic.recording;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

/**
 * A projection an agent leaves before closing a session: plausible futures for where the current
 * repo could go next. The graph renders the latest captured set as ghost futures.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CaptureProjectionRequest(
        @NotBlank String source,
        @NotBlank String clientSessionId,
        String repo,
        String basis,
        @Valid @NotEmpty @Size(max = 5) List<ProjectionPath> paths) {
}
