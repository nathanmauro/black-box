package dev.nathan.sbaagentic.recording;

/** Post-persistence event for a written or updated session summary. */
public record SessionSummaryRecorded(AgentSession session) {}
