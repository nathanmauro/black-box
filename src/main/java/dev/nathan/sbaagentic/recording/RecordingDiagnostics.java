package dev.nathan.sbaagentic.recording;

/** Read-only health of the recording module's storage, for operator diagnostics. */
public interface RecordingDiagnostics {

    /** Compares the FTS5 index with the canonical event table. Never repairs anything. */
    FullTextIndexStatus fullTextIndex();

    RedactionStatistics redaction();
}
