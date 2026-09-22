package dev.nathan.sbaagentic.recording;

/** Acknowledges canonical persistence, not optional indexing or summary completion. */
public record IdempotentIngestResponse(String captureId, String eventId, String sessionId, boolean replayed) {}
