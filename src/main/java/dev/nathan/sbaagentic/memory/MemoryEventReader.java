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

    /** Project scopes are exact canonical paths; null means global, empty means match nothing. */
    default List<AgentEvent> recallFiltered(
            List<String> eventTypes,
            String scopeLike,
            Instant since,
            int limit,
            List<String> projectScopes,
            boolean topicOnly,
            boolean includeSuperseded) {
        throw new UnsupportedOperationException("Filtered recall is not supported by this reader.");
    }

    default List<RecallCandidate> recallCandidatesFiltered(
            List<String> eventTypes, Instant since, List<String> projectScopes, boolean includeSuperseded) {
        throw new UnsupportedOperationException("Filtered recall is not supported by this reader.");
    }

    default java.util.Map<String, DecisionRelation> decisionRelations(List<String> eventIds) {

        return java.util.Map.of();
    }

    record DecisionRelation(String supersedesEventId, String supersededByEventId) {}

    record RecallCandidate(AgentEvent event, String cwd) {}
}
