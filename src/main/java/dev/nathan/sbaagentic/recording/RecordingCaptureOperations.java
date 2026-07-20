package dev.nathan.sbaagentic.recording;

/** Structured write operations over the canonical event recorder. */
public interface RecordingCaptureOperations {

    IngestResponse captureDecision(CaptureDecisionRequest request);

    IngestResponse captureHandoff(CaptureHandoffRequest request);

    IngestResponse captureObservation(String source, String clientSessionId, String repo, String text);
}
