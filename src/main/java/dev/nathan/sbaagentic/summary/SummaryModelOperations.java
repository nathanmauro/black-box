package dev.nathan.sbaagentic.summary;

/** Summary-owned model status and summarization boundary used by platform adapters. */
public interface SummaryModelOperations {

    AiHealth health();

    String complete(String system, String user, int maxTokens);
}
