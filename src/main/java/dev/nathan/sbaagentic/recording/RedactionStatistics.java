package dev.nathan.sbaagentic.recording;

/**
 * Process-lifetime redaction counters. Counts and configuration flags only: never a pattern, a
 * setting name, or any text that was inspected or replaced.
 *
 * @param ingestionRedactionEnabled whether the ingestion rules run at all
 * @param defaultRules {@code true} for the built-in rule set, {@code false} for custom patterns
 * @param configuredSecretValues how many of Black Box's own configured secret values are masked
 * @param ingest captured text and structured scalars, including every scalar inside a JSON tree
 * @param export text leaving through exports
 * @param summary text and stored JSON documents sent to a summary backend
 */
public record RedactionStatistics(
        boolean ingestionRedactionEnabled,
        boolean defaultRules,
        int configuredSecretValues,
        Channel ingest,
        Channel export,
        Channel summary) {

    /**
     * @param inspected non-null texts passed through this channel
     * @param redacted texts that came out different from how they went in
     * @param charsInspected total length of the inspected texts, in UTF-16 units
     */
    public record Channel(long inspected, long redacted, long charsInspected) {}
}
