package dev.nathan.sbaagentic.project.internal.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nathan.sbaagentic.project.ProjectAliasSnapshot;
import dev.nathan.sbaagentic.project.ProjectScopeOperations;
import dev.nathan.sbaagentic.project.internal.application.ContinuityReport.Finding;
import dev.nathan.sbaagentic.project.internal.application.ContinuityReport.Scan;
import dev.nathan.sbaagentic.project.internal.application.port.ContinuityStore;
import dev.nathan.sbaagentic.project.internal.application.port.ContinuityStore.BraidInput;
import dev.nathan.sbaagentic.project.internal.application.port.ContinuityStore.BraidRow;
import dev.nathan.sbaagentic.project.internal.application.port.ContinuityStore.SessionRow;
import dev.nathan.sbaagentic.project.internal.domain.KindTrailer;
import dev.nathan.sbaagentic.recording.ExportRedactor;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Read-only continuity lint over structured captures and saved braids. It flags handoffs nobody
 * picked up, decisions missing the {@code Kind:} trailer or rejected alternatives, braids whose
 * sessions are gone, and captures that no project scope can recall.
 */
@Service
public class ContinuityLintService {

    public static final String STALE_HANDOFF = "stale_handoff";
    public static final String DECISION_MISSING_KIND = "decision_missing_kind";
    public static final String DECISION_MISSING_ALTERNATIVES = "decision_missing_alternatives";
    public static final String ORPHAN_BRAID = "orphan_braid";
    public static final String BRAID_MISSING_SESSIONS = "braid_missing_sessions";
    public static final String UNDERSIZED_BRAID = "undersized_braid";
    public static final String UNSCOPED_CAPTURE = "unscoped_capture";

    static final int CAPTURE_SCAN_LIMIT = 50_000;
    static final int BRAID_SCAN_LIMIT = 5_000;

    private static final List<String> RULES = List.of(
            ORPHAN_BRAID,
            STALE_HANDOFF,
            DECISION_MISSING_KIND,
            DECISION_MISSING_ALTERNATIVES,
            BRAID_MISSING_SESSIONS,
            UNDERSIZED_BRAID,
            UNSCOPED_CAPTURE);

    private static final List<String> CAPTURE_TYPES =
            List.of(ContinuityCapture.DECISION, ContinuityCapture.HANDOFF, ContinuityCapture.OBSERVATION);

    private static final Map<String, Integer> SEVERITY_RANK = Map.of("error", 0, "warning", 1, "info", 2);

    private static final Comparator<Finding> FINDING_ORDER = Comparator.<Finding>comparingInt(
                    f -> SEVERITY_RANK.get(f.severity()))
            .thenComparing(Finding::observedAt, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(Finding::subjectId, Comparator.nullsLast(Comparator.naturalOrder()));

    private static final List<String> NOTES = List.of(
            "Read-only: nothing here writes, repairs or deletes.",
            "A handoff is stale when it is older than staleAfterHours and no decision or handoff in the same"
                    + " project was captured after it. Observations do not count as a pickup.",
            "Projects are the capture's repo, else its session's working directory, resolved through aliases.",
            "A Kind: trailer is a rationale or decision line that starts with 'Kind:'.",
            "Stored text is redacted before it is clipped into excerpts.");

    private final ContinuityStore store;
    private final ProjectScopeOperations scopes;
    private final ObjectMapper json;
    private final ContinuityText text;
    private final Clock clock;

    public ContinuityLintService(
            ContinuityStore store,
            ProjectScopeOperations scopes,
            ObjectMapper json,
            ExportRedactor redactor,
            Clock clock) {
        this.store = store;
        this.scopes = scopes;
        this.json = json;
        this.text = new ContinuityText(redactor);
        this.clock = clock;
    }

    public ContinuityReport lint(String project, int staleAfterHours, int limit) {
        if (staleAfterHours < 1 || staleAfterHours > 8760) {
            throw new IllegalArgumentException("staleAfterHours must be between 1 and 8760.");
        }
        if (limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("limit must be between 1 and 1000.");
        }
        String requested = project == null || project.isBlank() ? null : project;
        Instant now = clock.instant();
        ProjectAliasSnapshot aliases = scopes.snapshot();
        String wanted = requested == null ? null : ContinuityCapture.projectOf(requested, aliases);

        List<ContinuityCapture> captures =
                ContinuityCapture.from(store.captures(CAPTURE_TYPES, CAPTURE_SCAN_LIMIT), json, aliases);
        if (wanted != null) {
            captures = captures.stream().filter(c -> wanted.equals(c.project())).toList();
        }
        List<BraidRow> braids = braidsFor(wanted, aliases);

        List<Finding> findings = new ArrayList<>();
        findings.addAll(staleHandoffs(captures, now.minus(Duration.ofHours(staleAfterHours)), now));
        findings.addAll(decisionMetadata(captures));
        findings.addAll(unscoped(captures));
        findings.addAll(braidFindings(braids));
        findings.sort(FINDING_ORDER);

        Map<String, Integer> ruleCounts = new LinkedHashMap<>();
        RULES.forEach(rule -> ruleCounts.put(rule, 0));
        findings.forEach(f -> ruleCounts.merge(f.rule(), 1, Integer::sum));

        long capturesInStore = store.countCaptures(CAPTURE_TYPES);
        long braidsInStore = store.countBraids();
        Scan scan = new Scan(
                count(captures, ContinuityCapture.DECISION),
                count(captures, ContinuityCapture.HANDOFF),
                count(captures, ContinuityCapture.OBSERVATION),
                braids.size(),
                capturesInStore,
                braidsInStore,
                capturesInStore > CAPTURE_SCAN_LIMIT || braidsInStore > BRAID_SCAN_LIMIT);

        return new ContinuityReport(
                now.toString(),
                wanted == null ? null : text.clean(wanted, ContinuityText.PATH_CHARS),
                staleAfterHours,
                scan,
                ruleCounts,
                findings.size(),
                findings.size() > limit,
                findings.stream().limit(limit).map(this::sanitized).toList(),
                NOTES);
    }

    private List<Finding> staleHandoffs(List<ContinuityCapture> captures, Instant cutoff, Instant now) {
        Map<String, Instant> lastPickup = new HashMap<>();
        for (ContinuityCapture capture : captures) {
            if (capture.scoped() && (capture.is(ContinuityCapture.DECISION) || capture.is(ContinuityCapture.HANDOFF))) {
                lastPickup.merge(capture.project(), capture.observedAt(), (a, b) -> a.isAfter(b) ? a : b);
            }
        }
        List<Finding> findings = new ArrayList<>();
        for (ContinuityCapture handoff : captures) {
            if (!handoff.is(ContinuityCapture.HANDOFF) || !handoff.scoped()) {
                continue;
            }
            boolean pickedUp = lastPickup.get(handoff.project()).isAfter(handoff.observedAt());
            if (pickedUp || !handoff.observedAt().isBefore(cutoff)) {
                continue;
            }
            long hours = Duration.between(handoff.observedAt(), now).toHours();
            int openLoops = handoff.list("openLoops").size();
            boolean nextAction = handoff.field("nextAction") != null
                    && !handoff.field("nextAction").isBlank();
            String detail = "No decision or handoff in this project since; " + hours + "h old, " + openLoops
                    + " open loop(s), " + (nextAction ? "next action recorded" : "no next action") + ".";
            findings.add(finding(STALE_HANDOFF, "warning", handoff, excerptOf(handoff), detail));
        }

        return findings;
    }

    private List<Finding> decisionMetadata(List<ContinuityCapture> captures) {
        List<Finding> findings = new ArrayList<>();
        for (ContinuityCapture decision : captures) {
            if (!decision.is(ContinuityCapture.DECISION)) {
                continue;
            }
            String excerpt = excerptOf(decision);
            if (!KindTrailer.presentIn(decision.field("rationale"), decision.field("decision"))) {
                findings.add(finding(
                        DECISION_MISSING_KIND,
                        "warning",
                        decision,
                        excerpt,
                        "Rationale has no 'Kind: <kind> | ...' trailer line."));
            }
            if (decision.list("alternatives").isEmpty()) {
                findings.add(finding(
                        DECISION_MISSING_ALTERNATIVES,
                        "warning",
                        decision,
                        excerpt,
                        "No rejected alternatives were recorded."));
            }
        }

        return findings;
    }

    private List<Finding> unscoped(List<ContinuityCapture> captures) {

        return captures.stream()
                .filter(c -> !c.scoped())
                .map(c -> finding(
                        UNSCOPED_CAPTURE,
                        "info",
                        c,
                        excerptOf(c),
                        "Neither the capture's repo nor its session's working directory names a project,"
                                + " so project-scoped recall cannot find it."))
                .toList();
    }

    private List<Finding> braidFindings(List<BraidRow> braids) {
        List<Finding> findings = new ArrayList<>();
        for (BraidRow braid : braids) {
            int inputs = braid.inputs().size();
            long missing =
                    braid.inputs().stream().filter(i -> !i.sessionExists()).count();
            if (inputs == 0 || missing == inputs) {
                String detail = inputs == 0
                        ? "Braid has no input sessions."
                        : "None of the braid's " + inputs + " input session(s) exist.";
                findings.add(braidFinding(ORPHAN_BRAID, "error", braid, detail));
                continue;
            }
            if (missing > 0) {
                findings.add(braidFinding(
                        BRAID_MISSING_SESSIONS,
                        "warning",
                        braid,
                        missing + " of " + inputs + " input session(s) no longer exist."));
            }
            if (inputs < 2) {
                findings.add(braidFinding(
                        UNDERSIZED_BRAID,
                        "warning",
                        braid,
                        "Braid has " + inputs + " input session; braids weave 2-8."));
            }
        }

        return findings;
    }

    /**
     * Braids in scope: all of them without a project; with one, those saved to it or weaving at
     * least one of its sessions. A braid whose sessions are all gone and that names no project
     * cannot be attributed, so only the unfiltered report shows it.
     */
    private List<BraidRow> braidsFor(String wanted, ProjectAliasSnapshot aliases) {
        List<BraidRow> braids = store.braids(BRAID_SCAN_LIMIT);
        if (wanted == null) {

            return braids;
        }
        List<String> existing = braids.stream()
                .flatMap(b -> b.inputs().stream())
                .filter(BraidInput::sessionExists)
                .map(BraidInput::sessionId)
                .distinct()
                .toList();
        Set<String> inProject = store.sessions(existing).stream()
                .filter(s -> wanted.equals(ContinuityCapture.projectOf(s.cwd(), aliases)))
                .map(SessionRow::id)
                .collect(Collectors.toSet());

        return braids.stream()
                .filter(b -> b.projectKey() != null
                                && wanted.equals(ContinuityCapture.projectOf(b.projectKey(), aliases))
                        || b.inputs().stream().map(BraidInput::sessionId).anyMatch(inProject::contains))
                .toList();
    }

    private String excerptOf(ContinuityCapture capture) {
        String raw =
                switch (capture.eventType()) {
                    case ContinuityCapture.DECISION -> capture.field("decision");
                    case ContinuityCapture.HANDOFF -> capture.field("contextSummary");
                    default -> null;
                };

        return raw == null ? capture.text() : raw;
    }

    private Finding finding(String rule, String severity, ContinuityCapture capture, String excerpt, String detail) {

        return new Finding(
                rule,
                severity,
                capture.eventType().toLowerCase(Locale.ROOT),
                capture.eventId(),
                capture.sessionId(),
                capture.scoped() ? capture.project() : null,
                capture.observedAt().toString(),
                excerpt,
                detail);
    }

    private Finding braidFinding(String rule, String severity, BraidRow braid, String detail) {

        return new Finding(
                rule,
                severity,
                "braid",
                braid.id(),
                null,
                braid.projectKey(),
                braid.createdAt().toString(),
                braid.title(),
                detail);
    }

    /**
     * Findings are built from stored text and sorted raw; only the ones returned are redacted, so
     * a scan with thousands of findings does not redact excerpts it then discards. {@code detail}
     * is generated from counts and needs no redaction.
     */
    private Finding sanitized(Finding raw) {

        return new Finding(
                raw.rule(),
                raw.severity(),
                raw.subjectType(),
                text.clean(raw.subjectId(), ContinuityText.ID_CHARS),
                text.clean(raw.sessionId(), ContinuityText.ID_CHARS),
                text.clean(raw.project(), ContinuityText.PATH_CHARS),
                raw.observedAt(),
                text.clean(raw.excerpt(), ContinuityText.EXCERPT_CHARS),
                raw.detail());
    }

    private static int count(List<ContinuityCapture> captures, String type) {

        return (int) captures.stream().filter(c -> c.is(type)).count();
    }
}
