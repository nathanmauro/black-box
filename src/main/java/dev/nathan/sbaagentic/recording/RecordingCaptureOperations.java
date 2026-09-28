package dev.nathan.sbaagentic.recording;

/** Structured write operations over the canonical event recorder. */
public interface RecordingCaptureOperations {

    IngestResponse captureDecision(CaptureDecisionRequest request);

    IngestResponse captureHandoff(CaptureHandoffRequest request);

    IngestResponse captureProjection(CaptureProjectionRequest request);

    default IngestResponse captureIdea(CaptureIdeaRequest request) {

        return captureIdea(request, null);
    }

    /**
     * Captures an idea. {@code migratedFrom} is the source event id when an idea is migrated from an
     * earlier capture (for example an {@code [Idea]} observation); it is {@code null} otherwise.
     */
    IngestResponse captureIdea(CaptureIdeaRequest request, String migratedFrom);

    IngestResponse captureObservation(String source, String clientSessionId, String repo, String text);
}
