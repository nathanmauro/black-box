package dev.nathan.sbaagentic.runner;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

public record RunnerConfig(
        int concurrency,
        List<EngineConfig> engines,
        @JsonProperty("notify") String notifyCommand,
        List<RepoConfig> repos) {
}
