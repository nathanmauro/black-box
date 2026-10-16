package dev.nathan.sbaagentic.recording;

/** Mandatory built-in credential redaction for outbound content, independent of ingestion settings. */
public interface ExportRedactor {
    String redactForExport(String text);

    /**
     * Redaction for stored text that a summary backend reads: the built-in credential rules, without
     * {@link #redactForExport}'s fallback that drops the rest of a text after a quoted credential
     * key, so text holding no recognized credential is sent as stored.
     */
    default String redactForSummary(String text) {

        return redactForExport(text);
    }
}
