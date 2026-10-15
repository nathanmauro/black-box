package dev.nathan.sbaagentic.memory.internal.application;

import dev.nathan.sbaagentic.memory.internal.domain.ContinuationHandle;
import java.util.List;

/**
 * One restatement attempt. {@code entry} keeps the deterministic prompt; {@code synthesized} is null
 * unless the model reply passed validation, and {@code reason} is then a fixed code.
 */
public record ContinuationSynthesisResult(
        ContinuationReport report,
        ContinuationReport.Entry entry,
        Synthesized synthesized,
        String status,
        String reason) {

    public static final String OK = "ok";
    public static final String DEGRADED = "degraded";

    public record Synthesized(String text, String model, String promptVersion, List<ContinuationHandle> citations) {

        public Synthesized {
            citations = List.copyOf(citations);
        }
    }
}
