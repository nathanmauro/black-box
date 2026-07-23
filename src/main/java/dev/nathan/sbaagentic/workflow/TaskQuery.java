package dev.nathan.sbaagentic.workflow;

import java.util.List;

public record TaskQuery(
        String projectKey,
        String lane,
        TaskStatus status,
        List<TaskStatus> excludeStatuses,
        Integer limit,
        int offset) {

    public TaskQuery {
        excludeStatuses = excludeStatuses == null ? List.of() : List.copyOf(excludeStatuses);
    }

    public TaskQuery(String projectKey, String lane, TaskStatus status) {
        this(projectKey, lane, status, List.of(), null, 0);
    }

    public static TaskQuery all() {
        return new TaskQuery(null, null, null);
    }
}
