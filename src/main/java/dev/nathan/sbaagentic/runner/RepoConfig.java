package dev.nathan.sbaagentic.runner;

import com.fasterxml.jackson.annotation.JsonProperty;

public record RepoConfig(
        String path,
        boolean push,
        @JsonProperty("auto_merge") boolean autoMerge,
        String verify,
        String danger) {
}
