package dev.nathan.sbaagentic.runner.internal.client.blackbox;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record TaskSpec(
        String id,
        String projectKey,
        String title,
        String body,
        Map<String, Object> specRef,
        SpecStatus status,
        String createdBy,
        Instant createdAt,
        Instant updatedAt) {

    public TaskSpec {
        if (specRef != null) {
            specRef = Collections.unmodifiableMap(new LinkedHashMap<>(specRef));
        }
    }
}
