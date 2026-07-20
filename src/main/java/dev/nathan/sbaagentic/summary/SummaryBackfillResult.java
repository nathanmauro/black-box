package dev.nathan.sbaagentic.summary;

import java.util.List;

import dev.nathan.sbaagentic.recording.AgentSession;

public record SummaryBackfillResult(int requested, int summarized, List<AgentSession> sessions) {
}
