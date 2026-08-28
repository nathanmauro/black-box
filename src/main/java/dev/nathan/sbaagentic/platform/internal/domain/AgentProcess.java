package dev.nathan.sbaagentic.platform.internal.domain;

/**
 * A running agent process observed on the local system.
 */
public record AgentProcess(
        long pid,
        String agent,
        double cpuPercent,
        long rssKb,
        String elapsed) {
}
