package dev.nathan.sbaagentic.recording;

/** Canonical application entry point for recording one agent event. */
public interface EventRecorder {

    IngestResponse ingest(EventIngestRequest request);
}
