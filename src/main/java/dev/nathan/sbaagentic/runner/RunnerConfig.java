package dev.nathan.sbaagentic.runner;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record RunnerConfig(
        int concurrency,
        List<EngineConfig> engines,
        @JsonProperty("notify") String notifyCommand,
        List<RepoConfig> repos) {}
