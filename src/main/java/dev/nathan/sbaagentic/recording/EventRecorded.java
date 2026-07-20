package dev.nathan.sbaagentic.recording;

/** Synchronous post-persistence event with the optional search-index outcome. */
public final class EventRecorded {

    private final AgentSession session;
    private final AgentEvent event;
    private boolean indexed;

    public EventRecorded(AgentSession session, AgentEvent event) {
        this.session = session;
        this.event = event;
    }

    public AgentSession session() {
        return session;
    }

    public AgentEvent event() {
        return event;
    }

    public boolean indexed() {
        return indexed;
    }

    public void markIndexed() {
        indexed = true;
    }
}
