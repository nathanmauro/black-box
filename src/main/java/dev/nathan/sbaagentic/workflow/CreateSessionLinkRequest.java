package dev.nathan.sbaagentic.workflow;

public record CreateSessionLinkRequest(
        String parentSessionId,
        String childSessionId,
        String linkType,
        String taskId) {
}
