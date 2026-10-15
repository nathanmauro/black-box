package dev.nathan.sbaagentic.memory.internal.domain;

import java.time.Instant;
import java.util.List;

/**
 * One current Handoff or Decision recalled for a continuation. Every string field is recorded,
 * untrusted source text; {@code summary} is a handoff's context summary or a decision's choice.
 */
public record ContinuationCapture(
        String eventId,
        String sessionId,
        Kind kind,
        Instant observedAt,
        String summary,
        String rationale,
        List<String> openLoops,
        String nextAction) {

    public ContinuationCapture {
        openLoops = openLoops == null ? List.of() : openLoops.stream().filter(loop -> loop != null).toList();
    }

    public enum Kind {
        HANDOFF("handoff"),
        DECISION("decision");

        private final String wire;

        Kind(String wire) {
            this.wire = wire;
        }

        public String wire() {

            return wire;
        }
    }
}
