package dev.nathan.sbaagentic.memory.internal.application.port;

import dev.nathan.sbaagentic.recording.AgentEvent;
import java.time.Instant;
import java.util.List;

/** Read port for the Ideas view and the {@code [Idea]} observation migration. */
public interface IdeaEventReader {

    /**
     * Events whose {@code event_type} equals {@code eventType} exactly, newest first, each with its
     * session's working directory. When {@code textPrefix} is non-null only events whose text starts
     * with it (case-sensitive) are returned. At most {@code limit} rows.
     */
    default List<TypedEvent> eventsOfType(String eventType, String textPrefix, int limit) {

        return eventsOfType(eventType, textPrefix, null, limit);
    }

    /**
     * One keyset page of {@link #eventsOfType(String, String, int)}: ordered newest first by
     * (observed instant, id), and when {@code before} is non-null only rows strictly after that
     * position in the order are returned. Callers page by passing the last row of a page.
     */
    List<TypedEvent> eventsOfType(String eventType, String textPrefix, Cursor before, int limit);

    record TypedEvent(AgentEvent event, String cwd) {

        public Cursor cursor() {

            return new Cursor(event.observedAt(), event.id());
        }
    }

    /** Keyset position in the newest-first (observed instant, id) order. */
    record Cursor(Instant observedAt, String id) {}
}
