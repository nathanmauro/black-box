package dev.nathan.sbaagentic.task;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record TaskEvent(
        String id,
        String taskId,
        TaskEventType type,
        String actor,
        TaskStatus fromStatus,
        TaskStatus toStatus,
        Map<String, Object> detail,
        Instant observedAt) {

    public TaskEvent {
        if (detail != null) {
            detail = Collections.unmodifiableMap(new LinkedHashMap<>(detail));
        }
    }
}
