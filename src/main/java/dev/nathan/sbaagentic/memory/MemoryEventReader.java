package dev.nathan.sbaagentic.memory;

import java.time.Instant;
import java.util.List;

import dev.nathan.sbaagentic.recording.AgentEvent;

/** Read-only event projections owned by memory rather than canonical recording persistence. */
public interface MemoryEventReader {

    List<AgentEvent> searchEvents(String query, int limit);

    List<String> distinctFieldValues(String field, String prefix, int limit);

    List<AgentEvent> recall(List<String> eventTypes, String scopeLike, Instant since, int limit);
}
