package dev.nathan.sbaagentic.recording;

/** Canonical application entry point for recording one agent event. */
public interface EventRecorder {

    IngestResponse ingest(EventIngestRequest request);

    default IdempotentIngestResponse ingestIdempotent(IdempotentEventIngestRequest request) {
        throw new UnsupportedOperationException("Idempotent capture is not supported by this recorder.");
    }
}
