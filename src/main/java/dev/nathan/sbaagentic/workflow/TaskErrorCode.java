package dev.nathan.sbaagentic.workflow;

public enum TaskErrorCode {
    VALIDATION_FAILED,
    SPEC_NOT_FOUND,
    TASK_NOT_FOUND,
    INVALID_TRANSITION,
    CLAIMANT_MISMATCH,
    CONCURRENT_MODIFICATION,
    HANDOFF_FAILED
}
