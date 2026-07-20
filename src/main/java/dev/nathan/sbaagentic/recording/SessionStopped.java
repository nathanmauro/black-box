package dev.nathan.sbaagentic.recording;

/** Published after a final lifecycle event has been durably recorded. */
public record SessionStopped(AgentSession session, AgentEvent event) {
}
