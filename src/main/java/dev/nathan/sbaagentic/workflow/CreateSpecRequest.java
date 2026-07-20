package dev.nathan.sbaagentic.workflow;

import java.util.Map;

public record CreateSpecRequest(
        String projectKey,
        String title,
        String body,
        Map<String, Object> specRef,
        String actor) {
}
