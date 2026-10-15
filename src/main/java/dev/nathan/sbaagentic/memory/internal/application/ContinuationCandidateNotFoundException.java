package dev.nathan.sbaagentic.memory.internal.application;

/** The requested candidate is not among the current deterministic candidates; the record may have moved on. */
public class ContinuationCandidateNotFoundException extends RuntimeException {

    public ContinuationCandidateNotFoundException() {
        super("Candidate not found in the current record; build prompts again.");
    }
}
