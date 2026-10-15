package dev.nathan.sbaagentic.memory.internal.application;

import dev.nathan.sbaagentic.memory.ContinuationModelReply;
import dev.nathan.sbaagentic.memory.ContinuationSynthesisProperties;
import dev.nathan.sbaagentic.memory.ContinuationSynthesizer;
import dev.nathan.sbaagentic.memory.internal.domain.ContinuationSynthesisInput;
import dev.nathan.sbaagentic.memory.internal.domain.ContinuationSynthesisPrompt;
import dev.nathan.sbaagentic.memory.internal.domain.ContinuationSynthesisValidator;
import dev.nathan.sbaagentic.recording.ExportRedactor;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Optional restatement of one deterministic continuation by a configured local model. It recomputes
 * the candidates, makes at most one model call, validates the whole reply and never replaces the
 * deterministic prompt. Nothing is cached, logged or run in the background.
 */
@Service
public class ContinuationSynthesisService {

    public static final String DISABLED = "disabled";
    public static final String INPUT_OVER_BUDGET = "input_over_budget";
    public static final String UNAVAILABLE = "unavailable";
    static final Set<String> REASONS = Set.of(
            DISABLED,
            "remote_model_not_allowed",
            INPUT_OVER_BUDGET,
            UNAVAILABLE,
            "timeout",
            ContinuationSynthesisValidator.TRUNCATED,
            ContinuationSynthesisValidator.INVALID_REFERENCE,
            ContinuationSynthesisValidator.EMPTY);

    private final ContinuationService continuations;
    private final ObjectProvider<ContinuationSynthesizer> synthesizers;
    private final ExportRedactor redactor;
    private final ContinuationSynthesisProperties properties;

    public ContinuationSynthesisService(
            ContinuationService continuations,
            ObjectProvider<ContinuationSynthesizer> synthesizers,
            ExportRedactor redactor,
            ContinuationSynthesisProperties properties) {
        this.continuations = continuations;
        this.synthesizers = synthesizers;
        this.redactor = redactor;
        this.properties = properties;
    }

    /** True only when a synthesizer is configured and ready; reads configuration, never the network. */
    public boolean available() {
        ContinuationSynthesizer synthesizer = synthesizers.getIfAvailable();

        return synthesizer != null && synthesizer.unavailableReason() == null;
    }

    public ContinuationSynthesisResult synthesize(String project, Integer withinHours, String candidateId) {
        if (candidateId == null || candidateId.isBlank()) {
            throw new IllegalArgumentException("candidateId is required.");
        }
        ContinuationReport report = continuations.build(project, withinHours, ContinuationService.MAX_CANDIDATES);
        ContinuationReport.Entry entry = report.candidates().stream()
                .filter(candidate -> candidate.candidate().id().equals(candidateId.strip()))
                .findFirst()
                .orElseThrow(ContinuationCandidateNotFoundException::new);

        ContinuationSynthesizer synthesizer = synthesizers.getIfAvailable();
        if (synthesizer == null) {

            return degraded(report, entry, DISABLED);
        }
        String unavailable = synthesizer.unavailableReason();
        if (unavailable != null) {

            return degraded(report, entry, unavailable);
        }
        ContinuationSynthesisInput input =
                ContinuationSynthesisPrompt.build(entry.promptInput(), redactor::redactForExport);
        if (input.user().length() > synthesizer.maxInputChars()) {

            return degraded(report, entry, INPUT_OVER_BUDGET);
        }
        ContinuationModelReply reply =
                synthesizer.complete(ContinuationSynthesisPrompt.SYSTEM, input.user(), properties.getMaxOutputTokens());
        if (reply == null || reply.failed()) {

            return degraded(report, entry, reply == null ? UNAVAILABLE : reply.failureReason());
        }
        ContinuationSynthesisValidator.Verdict verdict = ContinuationSynthesisValidator.validate(
                reply.content(), reply.finishReason(), input.handles(), properties.getMaxOutputChars());
        if (!verdict.valid()) {

            return degraded(report, entry, verdict.reason());
        }

        return new ContinuationSynthesisResult(
                report,
                entry,
                new ContinuationSynthesisResult.Synthesized(
                        verdict.text(), reply.model(), ContinuationSynthesisPrompt.VERSION, verdict.citations()),
                ContinuationSynthesisResult.OK,
                null);
    }

    private static ContinuationSynthesisResult degraded(
            ContinuationReport report, ContinuationReport.Entry entry, String reason) {

        return new ContinuationSynthesisResult(
                report,
                entry,
                null,
                ContinuationSynthesisResult.DEGRADED,
                REASONS.contains(reason) ? reason : UNAVAILABLE);
    }
}
