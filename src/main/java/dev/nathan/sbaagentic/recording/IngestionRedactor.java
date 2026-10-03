package dev.nathan.sbaagentic.recording;

/** Configurable sanitization for newly accepted content, respecting ingestion redaction settings. */
public interface IngestionRedactor {
    /** Sanitize a string, clipping oversized scalars before scanning when redaction is enabled. */
    String redact(String text);

    /**
     * Sanitize JSON member names and values recursively. Maps remain maps and lists remain lists;
     * default secret-key rules may replace an entire member value. Disabled redaction returns the input.
     */
    Object redactDeep(Object value);
}
