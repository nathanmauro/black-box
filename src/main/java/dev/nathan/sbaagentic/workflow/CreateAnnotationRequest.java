package dev.nathan.sbaagentic.workflow;

import java.util.Map;

public record CreateAnnotationRequest(
        String taskId,
        String actor,
        String kind,
        String text,
        Map<String, Object> dataJson) {
}
