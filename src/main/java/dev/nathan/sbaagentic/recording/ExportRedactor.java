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

    /**
     * {@link #redactForSummary} for a stored tool input or output, which ingestion stored as JSON after
     * redacting each of its strings on its own: each string is redacted the same way here, so a value
     * never runs on into the JSON around it, and JSON that holds no recognized credential is sent as
     * stored. Like any stored text, it is read up to the scan budget: see
     * {@link #redactJsonForSummary(String, int)}. Text that is not JSON is redacted whole.
     */
    default String redactJsonForSummary(String json) {

        return redactForSummary(json);
    }

    /**
     * {@link #redactJsonForSummary(String)} for a reader that shows at most {@code maxChars} characters
     * of the JSON: in document order, members and elements are read until {@code maxChars} characters
     * of names, strings and other values have been read, each member, value, array and object counting
     * as at least one, so JSON made of empty strings, names or containers is cut like any other. Each
     * string read is redacted whole, and the marker {@code …[truncated]} stands in for the rest, so the
     * JSON stays valid and the work stays within that budget plus one string, however long the stored
     * JSON is.
     */
    default String redactJsonForSummary(String json, int maxChars) {

        return redactJsonForSummary(json);
    }
}
