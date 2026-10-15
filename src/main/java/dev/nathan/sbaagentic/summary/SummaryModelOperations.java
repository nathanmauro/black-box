package dev.nathan.sbaagentic.summary;

/** Summary-owned model status and summarization boundary used by platform adapters. */
public interface SummaryModelOperations {

    AiHealth health();

    String complete(String system, String user, int maxTokens);

    /**
     * Like {@link #complete} but also returns the finish reason and served model, and an empty
     * reply instead of throwing, so a caller can reject a truncated or empty answer itself. It
     * throws when local AI is disabled or the call fails.
     */
    ModelCompletion completeWithMetadata(String system, String user, int maxTokens);
}
