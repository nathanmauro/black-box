package dev.nathan.sbaagentic.memory.internal.application;

import dev.nathan.sbaagentic.memory.internal.domain.ContinuationCandidate;
import dev.nathan.sbaagentic.memory.internal.domain.ContinuationFrontier;
import dev.nathan.sbaagentic.memory.internal.domain.ContinuationLink;
import dev.nathan.sbaagentic.memory.internal.domain.ContinuationPromptInput;
import dev.nathan.sbaagentic.project.ProjectScope;
import java.time.Instant;
import java.util.List;

/** One deterministic continuation build for an exact logical project; nothing is stored. */
public record ContinuationReport(
        ProjectScope root,
        List<ProjectScope> scopes,
        Instant generatedAt,
        int withinHours,
        ContinuationFrontier frontier,
        int handoffsConsidered,
        int decisionsConsidered,
        int poolCap,
        List<Entry> candidates,
        List<Warning> warnings) {

    public ContinuationReport {
        scopes = List.copyOf(scopes);
        candidates = List.copyOf(candidates);
        warnings = List.copyOf(warnings);
    }

    /**
     * A selected candidate with its rendered prompt, the sources the prompt links and the material it
     * quotes, which an optional restatement reuses.
     */
    public record Entry(
            ContinuationCandidate candidate,
            String title,
            String why,
            String prompt,
            List<ContinuationLink> evidence,
            List<Caveat> caveats,
            ContinuationPromptInput promptInput) {

        public Entry {
            evidence = List.copyOf(evidence);
            caveats = List.copyOf(caveats);
        }
    }

    /** {@code link} is null unless the caveat names a recorded source. */
    public record Caveat(String code, String message, ContinuationLink link) {}

    public record Warning(String code, String message) {}
}
