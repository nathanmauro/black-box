package dev.nathan.sbaagentic.workflow.internal.domain;

import dev.nathan.sbaagentic.workflow.TaskEventType;
import dev.nathan.sbaagentic.workflow.TaskStatus;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record TaskUpdate(
        String taskId,
        TaskStatus expectedStatus,
        TaskStatus targetStatus,
        String actor,
        TaskEventType eventType,
        String claimedBy,
        String blockedReason,
        String resultHandoffId,
        Map<String, Object> detail) {

    public TaskUpdate {
        if (detail != null) {
            detail = Collections.unmodifiableMap(new LinkedHashMap<>(detail));
        }
    }
}
