package dev.nathan.sbaagentic.summary;

/** Transitional public model boundary; ask replaces this dependency with its own adapter. */
public interface SummaryModelOperations {

    AiHealth health();

    String complete(String system, String user, int maxTokens);
}
