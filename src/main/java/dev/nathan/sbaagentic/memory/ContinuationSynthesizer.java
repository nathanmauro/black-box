package dev.nathan.sbaagentic.memory;

/**
 * Optional model boundary for restating one continuation prompt. An implementation exists only when
 * {@code sba.continuation.synthesis.enabled=true}; memory never calls it in the background.
 */
public interface ContinuationSynthesizer {

    /** Null when a call may be attempted; otherwise a fixed reason code. Reads configuration only. */
    String unavailableReason();

    /** Input window in characters. Longer input is refused by the caller, never elided. */
    int maxInputChars();

    /** Exactly one model call. Failures come back as a reason code; an exception never escapes. */
    ContinuationModelReply complete(String system, String user, int maxTokens);
}
