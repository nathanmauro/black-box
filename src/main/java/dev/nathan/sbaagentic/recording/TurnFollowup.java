package dev.nathan.sbaagentic.recording;

import java.util.List;

/**
 * What the agent did after one event, up to (not including) the session's next human turn.
 * {@code complete} is false when the cap cut the turn short.
 */
public record TurnFollowup(String eventId, List<AgentEvent> events, boolean complete) {}
