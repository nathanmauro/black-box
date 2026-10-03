package dev.nathan.sbaagentic.lineage;

public record CreateSessionLinkRequest(String parentSessionId, String childSessionId, String linkType) {}
