package dev.nathan.sbaagentic.task;

public record TaskQuery(String projectKey, String lane, TaskStatus status) {

    public static TaskQuery all() {
        return new TaskQuery(null, null, null);
    }
}
