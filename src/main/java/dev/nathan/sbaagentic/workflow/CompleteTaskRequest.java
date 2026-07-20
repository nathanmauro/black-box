package dev.nathan.sbaagentic.workflow;

import java.util.List;

public record CompleteTaskRequest(
        String taskId,
        String actor,
        String source,
        String clientSessionId,
        String summary,
        List<String> openLoops,
        String nextAction) {
}
