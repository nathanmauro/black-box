package dev.nathan.sbaagentic.search;

import dev.nathan.sbaagentic.recording.AgentEvent;
import dev.nathan.sbaagentic.recording.AgentSession;

public interface EventIndexSink {

    boolean index(AgentSession session, AgentEvent event);
}
