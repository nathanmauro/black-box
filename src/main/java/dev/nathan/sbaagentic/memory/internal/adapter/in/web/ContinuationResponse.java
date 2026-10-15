package dev.nathan.sbaagentic.memory.internal.adapter.in.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.nathan.sbaagentic.memory.internal.application.ContinuationReport;
import dev.nathan.sbaagentic.memory.internal.domain.ContinuationLink;
import dev.nathan.sbaagentic.project.ProjectScope;
import java.time.Instant;
import java.util.List;

/**
 * {@code GET /api/continuations}: deterministic prompts built from one project's record. Hrefs are
 * relative Recall evidence links; {@code warnings} always carries the historical-evidence caveat.
 */
public record ContinuationResponse(
        Project project,
        Instant generatedAt,
        Instant newestEvidenceAt,
        Instant headAt,
        boolean stale,
        int withinHours,
        String mode,
        Retrieval retrieval,
        List<Candidate> candidates,
        List<Warning> warnings) {

    public static final String MODE = "deterministic";

    static ContinuationResponse from(ContinuationReport report) {

        return new ContinuationResponse(
                new Project(
                        report.root().canonicalKey(),
                        report.root().projectKey(),
                        report.root().label(),
                        report.scopes()),
                report.generatedAt(),
                report.frontier().newestEvidenceAt(),
                report.frontier().headAt(),
                report.frontier().stale(),
                report.withinHours(),
                MODE,
                new Retrieval(report.handoffsConsidered(), report.decisionsConsidered(), report.poolCap(), true),
                report.candidates().stream().map(Candidate::from).toList(),
                report.warnings().stream()
                        .map(warning -> new Warning(warning.code(), warning.message()))
                        .toList());
    }

    public record Project(String canonicalKey, String projectKey, String label, List<ProjectScope> scopes) {}

    /** Captures considered per kind; each kind is one exact project recall capped at {@code poolCap}. */
    public record Retrieval(int handoffs, int decisions, int poolCap, boolean excludedReplaced) {}

    public record Candidate(
            String id,
            int rank,
            String basis,
            String title,
            String why,
            int ageDays,
            boolean stale,
            String prompt,
            List<Evidence> evidence,
            List<Caveat> caveats) {

        static Candidate from(ContinuationReport.Entry entry) {

            return new Candidate(
                    entry.candidate().id(),
                    entry.candidate().rank(),
                    entry.candidate().basis().wire(),
                    entry.title(),
                    entry.why(),
                    entry.candidate().ageDays(),
                    entry.candidate().stale(),
                    entry.prompt(),
                    entry.evidence().stream().map(Evidence::from).toList(),
                    entry.caveats().stream().map(Caveat::from).toList());
        }
    }

    /** A source the prompt links; {@code observedAt} is the recorded time, even when dated in the future. */
    public record Evidence(
            String eventId, String sessionId, String kind, String field, Instant observedAt, String href) {

        static Evidence from(ContinuationLink link) {

            return new Evidence(
                    link.eventId(), link.sessionId(), link.kind(), link.field(), link.observedAt(), link.href());
        }
    }

    public record Caveat(
            String code,
            String message,
            @JsonInclude(JsonInclude.Include.NON_NULL) String eventId,
            @JsonInclude(JsonInclude.Include.NON_NULL) String href) {

        static Caveat from(ContinuationReport.Caveat caveat) {
            ContinuationLink link = caveat.link();

            return new Caveat(
                    caveat.code(),
                    caveat.message(),
                    link == null ? null : link.eventId(),
                    link == null ? null : link.href());
        }
    }

    public record Warning(String code, String message) {}
}
