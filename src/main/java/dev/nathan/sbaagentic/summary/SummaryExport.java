package dev.nathan.sbaagentic.summary;

public record SummaryExport(
        String sessionId,
        String targetId,
        String targetLabel,
        String targetType,
        String path,
        String relativePath) {
}
