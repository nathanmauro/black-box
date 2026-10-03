package dev.nathan.sbaagentic.recording;

/** Canonical application entry point for recording one agent event. */
public interface EventRecorder {

    IngestResponse ingest(EventIngestRequest request);

    default IngestResponse ingestDecisionReplacement(
            EventIngestRequest request, String supersedes, java.util.List<String> projectScopes) {
        throw new UnsupportedOperationException("Decision replacement is not supported by this recorder.");
    }

    default IdempotentIngestResponse ingestIdempotent(IdempotentEventIngestRequest request) {
        throw new UnsupportedOperationException("Idempotent capture is not supported by this recorder.");
    }
}
