package dev.nathan.sbaagentic.memory.internal.domain;

/** Why a candidate exists, in rank order: recorded next steps first, then open loops. */
public enum ContinuationBasis {
    HANDOFF_NEXT_ACTION("handoff.nextAction", "nextAction", 1),
    HANDOFF_OPEN_LOOP("handoff.openLoop", "openLoops", 2),
    DECISION_OPEN_LOOP("decision.openLoop", "openLoops", 3);

    private final String wire;
    private final String field;
    private final int tier;

    ContinuationBasis(String wire, String field, int tier) {
        this.wire = wire;
        this.field = field;
        this.tier = tier;
    }

    public String wire() {

        return wire;
    }

    /** The recorded field the text came from. */
    public String field() {

        return field;
    }

    public int tier() {

        return tier;
    }

    public boolean openLoop() {

        return this != HANDOFF_NEXT_ACTION;
    }
}
