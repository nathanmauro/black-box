package dev.nathan.sbaagentic.recording;

/** Mandatory built-in credential redaction for outbound content, independent of ingestion settings. */
public interface ExportRedactor {
    String redactForExport(String text);
}
