package dev.nathan.sbaagentic.search;

import dev.nathan.sbaagentic.event.AgentEvent;
import dev.nathan.sbaagentic.session.AgentSession;

public interface EventIndexSink {

    boolean index(AgentSession session, AgentEvent event);
}
