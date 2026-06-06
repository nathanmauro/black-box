package dev.nathan.sbaagentic.ask;

public record AskCitation(
        int number,
        String id,
        String title,
        String source,
        String sourcePath,
        String sessionId,
        String clientSessionId,
        String timestamp,
        String snippet,
        double score) {
}
