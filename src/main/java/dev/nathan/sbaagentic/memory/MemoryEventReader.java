package dev.nathan.sbaagentic.memory;

import dev.nathan.sbaagentic.recording.AgentEvent;
import java.time.Instant;
import java.util.List;

/** Read-only event projections owned by memory rather than canonical recording persistence. */
public interface MemoryEventReader {

    default List<AgentEvent> searchEvents(String query, List<String> projectScopes, int limit) {

        return searchEvents(query, projectScopes, limit, false);
    }

    /** {@code humanOnly} restricts matches to classified human turns (their raw {@code text} is still searched). */
    List<AgentEvent> searchEvents(String query, List<String> projectScopes, int limit, boolean humanOnly);

    default List<AgentEvent> searchEvents(String query, int limit) {

        return searchEvents(query, List.of(), limit);
    }

    List<String> distinctFieldValues(String field, String prefix, int limit);

    List<AgentEvent> recall(List<String> eventTypes, String scopeLike, Instant since, int limit);

    List<RecallCandidate> recallCandidates(List<String> eventTypes, Instant since);

    record RecallCandidate(AgentEvent event, String cwd) {}
}
