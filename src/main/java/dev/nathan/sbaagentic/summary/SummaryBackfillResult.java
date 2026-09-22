package dev.nathan.sbaagentic.summary;

import dev.nathan.sbaagentic.recording.AgentSession;
import java.util.List;

public record SummaryBackfillResult(int requested, int summarized, List<AgentSession> sessions) {}
