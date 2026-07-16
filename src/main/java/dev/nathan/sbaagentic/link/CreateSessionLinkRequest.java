package dev.nathan.sbaagentic.link;

public record CreateSessionLinkRequest(
        String parentSessionId,
        String childSessionId,
        String linkType,
        String taskId) {
}
