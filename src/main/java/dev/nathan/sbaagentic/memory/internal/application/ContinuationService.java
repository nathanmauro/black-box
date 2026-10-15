package dev.nathan.sbaagentic.memory.internal.application;

import dev.nathan.sbaagentic.memory.MemoryRecallOperations;
import dev.nathan.sbaagentic.memory.RecalledItem;
import dev.nathan.sbaagentic.memory.internal.domain.ContinuationCandidate;
import dev.nathan.sbaagentic.memory.internal.domain.ContinuationCapture;
import dev.nathan.sbaagentic.memory.internal.domain.ContinuationCapture.Kind;
import dev.nathan.sbaagentic.memory.internal.domain.ContinuationFrontier;
import dev.nathan.sbaagentic.memory.internal.domain.ContinuationLink;
import dev.nathan.sbaagentic.memory.internal.domain.ContinuationPromptInput;
import dev.nathan.sbaagentic.memory.internal.domain.ContinuationPromptRenderer;
import dev.nathan.sbaagentic.memory.internal.domain.ContinuationRanker;
import dev.nathan.sbaagentic.memory.internal.domain.ContinuationRelation;
import dev.nathan.sbaagentic.memory.internal.domain.ContinuationRelation.Relation;
import dev.nathan.sbaagentic.memory.internal.domain.ContinuationText;
import dev.nathan.sbaagentic.project.ProjectKey;
import dev.nathan.sbaagentic.project.ProjectScope;
import dev.nathan.sbaagentic.project.ProjectScopeOperations;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Builds prompts a fresh agent could start from, on demand, from one project's recorded handoffs
 * and current decisions. It reads through exact project recall only (alias-expanded, replaced
 * decisions excluded), calls no model, and stores nothing: every request reflects the latest
 * captures and has nothing to invalidate.
 */
@Service
public class ContinuationService {

    public static final int DEFAULT_WITHIN_HOURS = 8_760;
    public static final int MAX_WITHIN_HOURS = 8_760;
    public static final int DEFAULT_MAX = 3;
    public static final int MAX_CANDIDATES = 3;
    public static final int POOL_CAP = 50;
    public static final String HISTORICAL_WARNING =
            "Historical evidence does not prove an action is still relevant or unfinished.";

    static final int MAX_LINKED_SOURCES = 5;
    static final int MAX_RELATIONS = 3;
    static final int MAX_CONTEXT_DECISIONS = 3;
    static final int MAX_OTHER_LOOPS = 5;
    static final int MAX_PROJECT_CHARS = 4_096;
    private static final int RELATION_SCAN_LIMIT = 200;

    private final MemoryRecallOperations recall;
    private final ProjectScopeOperations projects;
    private final EvidenceService evidence;
    private final Clock clock;

    public ContinuationService(
            MemoryRecallOperations recall, ProjectScopeOperations projects, EvidenceService evidence, Clock clock) {
        this.recall = recall;
        this.projects = projects;
        this.evidence = evidence;
        this.clock = clock;
    }

    public ContinuationReport build(String project, Integer withinHours, Integer max) {
        String requested = requireProject(project);
        int hours = bounded("withinHours", withinHours, DEFAULT_WITHIN_HOURS, MAX_WITHIN_HOURS);
        int limit = bounded("max", max, DEFAULT_MAX, MAX_CANDIDATES);
        List<ProjectScope> scopes = projects.projectScopesFor(requested);
        ProjectScope root = scopes.getFirst();
        Instant now = clock.instant();

        // One recall per kind so frequent decisions cannot crowd handoffs out of the shared pool.
        List<RecalledItem> handoffs = recall.recall(
                        null, root.canonicalKey(), "", hours, List.of("handoff"), POOL_CAP, false)
                .items();
        List<RecalledItem> decisions = recall.recall(
                        null, root.canonicalKey(), "", hours, List.of("decision"), POOL_CAP, false)
                .items();
        ContinuationFrontier frontier = ContinuationRanker.rank(
                root.canonicalKey(), captures(handoffs, Kind.HANDOFF), captures(decisions, Kind.DECISION), now);
        List<ContinuationCandidate> selected =
                frontier.candidates().stream().limit(limit).toList();
        Map<String, List<ContinuationRelation>> relations = relations(selected, root.canonicalKey());
        List<ContinuationReport.Entry> entries = selected.stream()
                .map(candidate -> entry(
                        candidate, frontier, relations.getOrDefault(candidate.id(), List.of()), !handoffs.isEmpty()))
                .toList();

        return new ContinuationReport(
                root,
                scopes,
                now,
                hours,
                frontier,
                handoffs.size(),
                decisions.size(),
                POOL_CAP,
                entries,
                warnings(frontier, entries, handoffs.size(), decisions.size()));
    }

    /** Canonicalized like recall before validation: blank recall is global, so it is never accepted here. */
    static String requireProject(String project) {
        String canonical = ProjectKey.of(project).value();
        if (ProjectKey.NO_PROJECT.equals(canonical) || "/".equals(canonical)) {
            throw new IllegalArgumentException(
                    "project must name a project path; blank, \"/\" and the no-project scope are not accepted.");
        }
        if (canonical.length() > MAX_PROJECT_CHARS) {
            throw new IllegalArgumentException("project must be at most " + MAX_PROJECT_CHARS + " characters.");
        }

        return canonical;
    }

    private static int bounded(String name, Integer value, int fallback, int maximum) {
        if (value == null) {

            return fallback;
        }
        if (value < 1 || value > maximum) {
            throw new IllegalArgumentException(name + " must be between 1 and " + maximum + ".");
        }

        return value;
    }

    private static List<ContinuationCapture> captures(List<RecalledItem> items, Kind kind) {

        return items.stream()
                .filter(item -> item != null && item.supersededByEventId() == null)
                .map(item -> new ContinuationCapture(
                        item.eventId(),
                        item.sessionId(),
                        kind,
                        item.observedAt(),
                        item.headline(),
                        kind == Kind.DECISION ? item.rationale() : null,
                        item.openLoops(),
                        kind == Kind.HANDOFF ? item.nextAction() : null))
                .toList();
    }

    private ContinuationReport.Entry entry(
            ContinuationCandidate candidate,
            ContinuationFrontier frontier,
            List<ContinuationRelation> relations,
            boolean handoffsRecorded) {
        // The primary source supplies the goal text, so a cap on listed sources must never drop it.
        List<ContinuationLink> goal = candidate.primaryFirst().stream()
                .map(source -> new ContinuationLink(
                        source.capture().eventId(),
                        source.capture().sessionId(),
                        source.capture().kind().wire(),
                        source.field(),
                        source.capture().observedAt()))
                .toList();
        List<ContinuationLink> links = new ArrayList<>(goal.subList(0, Math.min(goal.size(), MAX_LINKED_SOURCES)));
        int omitted = goal.size() - links.size();
        relations.forEach(relation -> links.add(relation.link()));

        ContinuationCapture primary = candidate.primary().capture();
        ContinuationCapture state = primary.kind() == Kind.HANDOFF
                ? primary
                : frontier.frontierHandoffs().stream().findFirst().orElse(null);
        List<ContinuationCandidate> otherLoops = frontier.candidates().stream()
                .filter(other ->
                        !other.id().equals(candidate.id()) && other.basis().openLoop())
                .limit(MAX_OTHER_LOOPS)
                .toList();
        ContinuationPromptInput promptInput = new ContinuationPromptInput(
                candidate,
                frontier.now(),
                frontier.stale(),
                frontier.headAt(),
                state,
                handoffsRecorded,
                frontier.decisions().stream().limit(MAX_CONTEXT_DECISIONS).toList(),
                otherLoops,
                relations,
                links,
                omitted);
        String prompt = ContinuationPromptRenderer.render(promptInput);

        List<ContinuationReport.Caveat> caveats = new ArrayList<>();
        for (ContinuationRelation relation : relations) {
            caveats.add(new ContinuationReport.Caveat(
                    "evidence_" + relation.relation().wire(),
                    "Evidence recorded on " + ContinuationText.day(relation.observedAt()) + " "
                            + relation.relation().wire()
                            + " a source of this prompt. It is an explicit recorded link, not a verification.",
                    relation.link()));
        }
        if (candidate.futureDated()) {
            caveats.add(new ContinuationReport.Caveat(
                    "future_dated",
                    "A source capture is dated after this response was built; it is treated as recorded now.",
                    null));
        }

        return new ContinuationReport.Entry(
                candidate,
                ContinuationPromptRenderer.title(candidate),
                ContinuationPromptRenderer.why(candidate),
                prompt,
                links,
                caveats,
                promptInput);
    }

    /** Explicit supports/refutes links only, refuting first; never a rank change or an inferred outcome. */
    private Map<String, List<ContinuationRelation>> relations(List<ContinuationCandidate> candidates, String root) {
        Set<String> ids = new LinkedHashSet<>();
        candidates.forEach(candidate ->
                candidate.sources().forEach(source -> ids.add(source.capture().eventId())));
        if (ids.isEmpty()) {

            return Map.of();
        }
        List<EvidenceView> linked = evidence.linkedTo(ids, root, RELATION_SCAN_LIMIT);
        Map<String, List<ContinuationRelation>> byCandidate = new HashMap<>();
        for (ContinuationCandidate candidate : candidates) {
            Map<String, String> sourceRefs = new HashMap<>();
            candidate
                    .sources()
                    .forEach(source -> sourceRefs.put(
                            "event:" + source.capture().eventId().toLowerCase(Locale.ROOT),
                            source.capture().eventId()));
            List<ContinuationRelation> refutes = new ArrayList<>();
            List<ContinuationRelation> supports = new ArrayList<>();
            for (EvidenceView view : linked) {
                String refuted = firstTarget(view.refutes(), sourceRefs);
                if (refuted != null) {
                    refutes.add(relation(view, Relation.REFUTES, refuted));
                    continue;
                }
                String supported = firstTarget(view.supports(), sourceRefs);
                if (supported != null) {
                    supports.add(relation(view, Relation.SUPPORTS, supported));
                }
            }
            List<ContinuationRelation> ordered = new ArrayList<>(refutes);
            ordered.addAll(supports);
            byCandidate.put(candidate.id(), List.copyOf(ordered.subList(0, Math.min(ordered.size(), MAX_RELATIONS))));
        }

        return byCandidate;
    }

    private static String firstTarget(List<String> links, Map<String, String> sourceRefs) {
        if (links == null) {

            return null;
        }
        for (String link : links) {
            String target = sourceRefs.get(link);
            if (target != null) {

                return target;
            }
        }

        return null;
    }

    private static ContinuationRelation relation(EvidenceView view, Relation relation, String target) {

        return new ContinuationRelation(
                view.eventId(),
                view.sessionId(),
                relation,
                view.capturedAt() == null ? view.observedAt() : view.capturedAt(),
                view.claim(),
                target);
    }

    private static List<ContinuationReport.Warning> warnings(
            ContinuationFrontier frontier, List<ContinuationReport.Entry> entries, int handoffs, int decisions) {
        List<ContinuationReport.Warning> warnings = new ArrayList<>();
        warnings.add(new ContinuationReport.Warning("historical_evidence", HISTORICAL_WARNING));
        if (entries.isEmpty()) {
            warnings.add(new ContinuationReport.Warning(
                    "insufficient_evidence",
                    frontier.empty()
                            ? "No handoff or decision was recorded for this project in the window; no prompt was"
                                    + " built."
                            : "No recorded next action or open loop qualified near the newest captures; no prompt"
                                    + " was padded."));
        }
        if (frontier.stale()) {
            warnings.add(new ContinuationReport.Warning(
                    "stale",
                    "The project's newest capture was recorded "
                            + ContinuationText.ago(ContinuationText.ageDays(frontier.headAt(), frontier.now()))
                            + "; treat these prompts as history, not current state."));
        }
        if (frontier.futureDated()) {
            warnings.add(new ContinuationReport.Warning(
                    "future_dated",
                    "Some captures are dated after this response was built and are treated as recorded now."));
        }
        List<String> capped = new ArrayList<>();
        if (handoffs >= POOL_CAP) {
            capped.add("handoffs");
        }
        if (decisions >= POOL_CAP) {
            capped.add("decisions");
        }
        if (!capped.isEmpty()) {
            warnings.add(new ContinuationReport.Warning(
                    "retrieval_cap",
                    "Only the newest " + POOL_CAP + " " + String.join(" and ", capped)
                            + " in the window were considered."));
        }

        return warnings;
    }
}
