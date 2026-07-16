package dev.nathan.sbaagentic.task;

import java.util.Map;

public record CreateAnnotationRequest(
        String taskId,
        String actor,
        String kind,
        String text,
        Map<String, Object> dataJson) {
}
