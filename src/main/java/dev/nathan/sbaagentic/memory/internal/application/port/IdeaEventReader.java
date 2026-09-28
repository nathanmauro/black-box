package dev.nathan.sbaagentic.memory.internal.application.port;

import dev.nathan.sbaagentic.recording.AgentEvent;
import java.util.List;

/** Read port for the Ideas view and the {@code [Idea]} observation migration. */
public interface IdeaEventReader {

    /**
     * Events whose {@code event_type} equals {@code eventType} exactly, newest first, each with its
     * session's working directory. When {@code textPrefix} is non-null only events whose text starts
     * with it (case-sensitive) are returned. At most {@code limit} rows.
     */
    List<TypedEvent> eventsOfType(String eventType, String textPrefix, int limit);

    record TypedEvent(AgentEvent event, String cwd) {}
}
