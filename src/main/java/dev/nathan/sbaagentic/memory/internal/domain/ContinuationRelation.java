package dev.nathan.sbaagentic.memory.internal.domain;

import java.time.Instant;

/**
 * An explicit, recorded Evidence link to one of a candidate's source events. It is shown as a
 * caveat; it never changes rank and never marks anything as resolved.
 */
public record ContinuationRelation(
        String eventId, String sessionId, Relation relation, Instant observedAt, String claim, String targetEventId) {

    public enum Relation {
        SUPPORTS("supports"),
        REFUTES("refutes");

        private final String wire;

        Relation(String wire) {
            this.wire = wire;
        }

        public String wire() {

            return wire;
        }
    }

    public ContinuationLink link() {

        return new ContinuationLink(eventId, sessionId, "evidence", relation.wire(), observedAt);
    }
}
