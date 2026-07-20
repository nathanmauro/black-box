package dev.nathan.sbaagentic.memory;

public record MemoryHit(
        String id,
        double score,
        String title,
        String source,
        String sourcePath,
        String sessionId,
        String clientSessionId,
        String timestamp,
        String text,
        String snippet) {

    public MemoryHit withScore(double nextScore) {
        return new MemoryHit(id, nextScore, title, source, sourcePath, sessionId, clientSessionId,
                timestamp, text, snippet);
    }
}
