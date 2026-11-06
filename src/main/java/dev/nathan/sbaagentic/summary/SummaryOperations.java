package dev.nathan.sbaagentic.summary;

import dev.nathan.sbaagentic.recording.AgentSession;

public interface SummaryOperations {

    /** Reads the session with the configured model, replacing any summary it has. */
    AgentSession summarize(String sessionId);

    /**
     * Summarizes a session that reached a final event or was left without a summary: from its
     * latest handoff when that still stands for the session, with the model otherwise.
     */
    AgentSession summarizeFinished(String sessionId);

    AgentSession summarize(String source, String clientSessionId);

    SummaryBackfillResult summarizeMissing(int limit);
}
