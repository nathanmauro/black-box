package dev.nathan.sbaagentic.recording;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** One plausible future path captured for a project's trajectory graph. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProjectionPath(
        String title,
        @JsonProperty(required = false) String description,
        @JsonProperty(required = false) Double confidence) {}
