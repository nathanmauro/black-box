package dev.nathan.sbaagentic.memory.internal.domain;

import java.time.Instant;

/**
 * One recorded field backing a candidate. {@code effectiveAt} is the recorded time, or the build time
 * when the capture is dated in the future.
 */
public record ContinuationSource(
        ContinuationCapture capture, ContinuationBasis basis, String text, Instant effectiveAt, boolean futureDated) {

    public String field() {

        return basis.field();
    }
}
