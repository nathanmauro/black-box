package dev.nathan.sbaagentic.summary;

import dev.nathan.sbaagentic.recording.AgentSession;

public interface SummaryOperations {

    AgentSession summarize(String sessionId);

    AgentSession summarize(String source, String clientSessionId);

    SummaryBackfillResult summarizeMissing(int limit);
}
