package dev.nathan.sbaagentic.memory.internal.adapter.in.web;

import dev.nathan.sbaagentic.memory.internal.application.ContinuationSynthesisResult;
import dev.nathan.sbaagentic.memory.internal.domain.ContinuationHandle;
import java.time.Instant;
import java.util.List;

/**
 * An optional model restatement beside the unchanged deterministic candidate. {@code synthesized} is
 * null unless the reply passed validation; {@code synthesis.reason} is then a fixed code.
 */
public record ContinuationSynthesisResponse(
        ContinuationResponse.Candidate candidate, Synthesized synthesized, Synthesis synthesis) {

    static ContinuationSynthesisResponse from(ContinuationSynthesisResult result) {
        ContinuationSynthesisResult.Synthesized synthesized = result.synthesized();

        return new ContinuationSynthesisResponse(
                ContinuationResponse.Candidate.from(result.entry()),
                synthesized == null
                        ? null
                        : new Synthesized(
                                synthesized.text(),
                                synthesized.model(),
                                synthesized.promptVersion(),
                                synthesized.citations().stream()
                                        .map(Citation::from)
                                        .toList()),
                new Synthesis(result.status(), result.reason()));
    }

    /** Model output that cites only the listed evidence; a restatement, not a verified account. */
    public record Synthesized(String text, String model, String promptVersion, List<Citation> citations) {}

    public record Citation(
            String handle, String eventId, String sessionId, String kind, Instant observedAt, String href) {

        static Citation from(ContinuationHandle handle) {

            return new Citation(
                    handle.handle(),
                    handle.eventId(),
                    handle.sessionId(),
                    handle.kind(),
                    handle.observedAt(),
                    handle.href());
        }
    }

    /** {@code status} is {@code ok} or {@code degraded}; {@code reason} is null when ok. */
    public record Synthesis(String status, String reason) {}
}
