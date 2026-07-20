package dev.nathan.sbaagentic.runner.internal.client.blackbox;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record TaskAnnotation(
        String id,
        String taskId,
        AnnotationKind kind,
        String actor,
        String text,
        Map<String, Object> dataJson,
        Instant observedAt) {

    public TaskAnnotation {
        if (dataJson != null) {
            dataJson = Collections.unmodifiableMap(new LinkedHashMap<>(dataJson));
        }
    }
}
