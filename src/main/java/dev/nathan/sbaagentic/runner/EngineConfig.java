package dev.nathan.sbaagentic.runner;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public record EngineConfig(
        String id,
        String model,
        String effort,
        String sandbox,
        String provider,
        boolean enabled) {

    // A missing or null JSON value keeps engines enabled by default.
    @JsonCreator
    public EngineConfig(
            @JsonProperty("id") String id,
            @JsonProperty("model") String model,
            @JsonProperty("effort") String effort,
            @JsonProperty("sandbox") String sandbox,
            @JsonProperty("provider") String provider,
            @JsonProperty("enabled") Boolean enabled) {
        this(id, model, effort, sandbox, provider, enabled == null || enabled);
    }
}
