package dev.nathan.sbaagentic.project.internal.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nathan.sbaagentic.project.ProjectAliasSnapshot;
import dev.nathan.sbaagentic.project.ProjectScopeOperations;
import dev.nathan.sbaagentic.project.internal.application.BraidGraph.Counts;
import dev.nathan.sbaagentic.project.internal.application.BraidGraph.Edge;
import dev.nathan.sbaagentic.project.internal.application.BraidGraph.Node;
import dev.nathan.sbaagentic.project.internal.application.port.ContinuityStore;
import dev.nathan.sbaagentic.project.internal.application.port.ContinuityStore.BraidInput;
import dev.nathan.sbaagentic.project.internal.application.port.ContinuityStore.BraidRow;
import dev.nathan.sbaagentic.project.internal.application.port.ContinuityStore.SessionRow;
import dev.nathan.sbaagentic.project.internal.domain.ProjectKeyCodec;
import dev.nathan.sbaagentic.recording.ExportRedactor;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/** Builds the read-only, redacted braid graph behind {@code GET /api/export/braid-graph}. */
@Service
public class BraidGraphExportService {

    static final int CAPTURE_SCAN_LIMIT = 20_000;
    static final int BRAID_SCAN_LIMIT = 1_000;
    static final int MAX_BRAIDS = 200;
    static final int MAX_CAPTURES_PER_SESSION = 25;
    public static final int MAX_SESSIONS_LIMIT = 200;

    private static final List<String> CAPTURE_TYPES = List.of(ContinuityCapture.DECISION, ContinuityCapture.HANDOFF);

    private static final List<String> NOTES = List.of(
            "Read-only export. Node ids are generated; ref is the stored id.",
            "Stored text is redacted before it is clipped into labels.",
            "carried_by links a decision to the next handoff in its session; picked_up_by links a handoff to"
                    + " the next session in its project.",
            "Sessions are the most recently active ones with decisions, handoffs or braids, up to maxSessions;"
                    + " each keeps its newest " + MAX_CAPTURES_PER_SESSION + " captures.");

    private final ContinuityStore store;
    private final ProjectScopeOperations scopes;
    private final ObjectMapper json;
    private final ContinuityText text;
    private final Clock clock;

    public BraidGraphExportService(
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

    public BraidGraph export(String project, int maxSessions) {
        if (maxSessions < 1 || maxSessions > MAX_SESSIONS_LIMIT) {
            throw new IllegalArgumentException("maxSessions must be between 1 and " + MAX_SESSIONS_LIMIT + ".");
        }
        ProjectAliasSnapshot aliases = scopes.snapshot();
        String wanted = project == null || project.isBlank() ? null : ContinuityCapture.projectOf(project, aliases);

        List<ContinuityCapture> captures =
                ContinuityCapture.from(store.captures(CAPTURE_TYPES, CAPTURE_SCAN_LIMIT), json, aliases);
        if (wanted != null) {
            captures = captures.stream().filter(c -> wanted.equals(c.project())).toList();
        }
        boolean truncated =
                store.countCaptures(CAPTURE_TYPES) > CAPTURE_SCAN_LIMIT || store.countBraids() > BRAID_SCAN_LIMIT;
        List<BraidRow> braids = store.braids(BRAID_SCAN_LIMIT);

        Set<String> wantedIds = new LinkedHashSet<>();
        captures.forEach(c -> wantedIds.add(c.sessionId()));
        braids.forEach(
                b -> b.inputs().stream().filter(BraidInput::sessionExists).forEach(i -> wantedIds.add(i.sessionId())));
        Map<String, SessionRow> sessionRows = store.sessions(wantedIds).stream()
                .collect(Collectors.toMap(SessionRow::id, Function.identity(), (a, b) -> a));
        Map<String, String> sessionProjects = sessionProjects(captures, sessionRows, aliases);

        if (wanted != null) {
            braids = braids.stream()
                    .filter(b -> inProject(b, wanted, aliases, sessionProjects))
                    .toList();
        }

        Map<String, Instant> activity = activity(captures, braids, sessionRows);
        List<String> ranked = activity.entrySet().stream()
                .sorted(Map.Entry.<String, Instant>comparingByValue()
                        .reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .map(Map.Entry::getKey)
                .toList();
        truncated |= ranked.size() > maxSessions;
        Set<String> kept = new HashSet<>(ranked.subList(0, Math.min(maxSessions, ranked.size())));

        List<BraidRow> keptBraids = braids.stream()
                .filter(b -> b.inputs().stream().noneMatch(BraidInput::sessionExists)
                        || b.inputs().stream().map(BraidInput::sessionId).anyMatch(kept::contains))
                .toList();
        truncated |= keptBraids.size() > MAX_BRAIDS;
        keptBraids = keptBraids.subList(0, Math.min(MAX_BRAIDS, keptBraids.size()));

        Map<String, List<ContinuityCapture>> bySession = new HashMap<>();
        for (ContinuityCapture capture : captures) {
            if (kept.contains(capture.sessionId())) {
                bySession
                        .computeIfAbsent(capture.sessionId(), id -> new ArrayList<>())
                        .add(capture);
            }
        }
        List<ContinuityCapture> keptCaptures = new ArrayList<>();
        for (List<ContinuityCapture> sessionCaptures : bySession.values()) {
            // Captures arrive newest first, so the head of each list is the newest.
            truncated |= sessionCaptures.size() > MAX_CAPTURES_PER_SESSION;
            keptCaptures.addAll(sessionCaptures.subList(0, Math.min(MAX_CAPTURES_PER_SESSION, sessionCaptures.size())));
        }

        Builder graph = new Builder(text);
        List<SessionRow> sessionNodes = kept.stream()
                .map(sessionRows::get)
                .sorted(Comparator.comparing(SessionRow::startedAt).thenComparing(SessionRow::id))
                .toList();
        sessionNodes.forEach(s -> graph.session(s, sessionProjects.get(s.id())));
        graph.captures(keptCaptures);
        graph.pickups(keptCaptures, sessionNodes, sessionProjects);
        graph.braids(keptBraids, kept);

        return new BraidGraph(
                clock.instant().toString(),
                wanted == null ? null : text.clean(wanted, ContinuityText.PATH_CHARS),
                maxSessions,
                graph.counts(),
                truncated,
                List.copyOf(graph.nodes),
                List.copyOf(graph.edges),
                NOTES);
    }

    /** A session's project is that of its newest capture, else its working directory's. */
    private static Map<String, String> sessionProjects(
            List<ContinuityCapture> captures, Map<String, SessionRow> sessions, ProjectAliasSnapshot aliases) {
        Map<String, String> projects = new HashMap<>();
        for (ContinuityCapture capture : captures) {
            projects.putIfAbsent(capture.sessionId(), capture.project());
        }
        for (SessionRow session : sessions.values()) {
            projects.computeIfAbsent(session.id(), id -> ContinuityCapture.projectOf(session.cwd(), aliases));
        }

        return projects;
    }

    private static boolean inProject(
            BraidRow braid, String wanted, ProjectAliasSnapshot aliases, Map<String, String> sessionProjects) {
        if (braid.projectKey() != null && wanted.equals(ContinuityCapture.projectOf(braid.projectKey(), aliases))) {

            return true;
        }

        return braid.inputs().stream().anyMatch(i -> wanted.equals(sessionProjects.get(i.sessionId())));
    }

    /** Latest activity per existing session: its newest capture or the newest braid weaving it. */
    private static Map<String, Instant> activity(
            List<ContinuityCapture> captures, List<BraidRow> braids, Map<String, SessionRow> sessions) {
        Map<String, Instant> activity = new HashMap<>();
        for (ContinuityCapture capture : captures) {
            if (sessions.containsKey(capture.sessionId())) {
                activity.merge(capture.sessionId(), capture.observedAt(), BraidGraphExportService::later);
            }
        }
        for (BraidRow braid : braids) {
            for (BraidInput input : braid.inputs()) {
                if (sessions.containsKey(input.sessionId())) {
                    activity.merge(input.sessionId(), braid.createdAt(), BraidGraphExportService::later);
                }
            }
        }

        return activity;
    }

    private static Instant later(Instant a, Instant b) {

        return a.isAfter(b) ? a : b;
    }

    /** Assigns generated node ids in a stable order and collects edges between them. */
    private static final class Builder {

        private final ContinuityText text;
        private final List<Node> nodes = new ArrayList<>();
        private final List<Edge> edges = new ArrayList<>();
        private final Map<String, String> sessionNodeIds = new HashMap<>();
        private final Map<String, String> handoffNodeIds = new HashMap<>();
        private final Map<String, String> missingNodeIds = new LinkedHashMap<>();
        private int decisions;
        private int handoffs;
        private int braids;

        Builder(ContinuityText text) {
            this.text = text;
        }

        void session(SessionRow session, String project) {
            String id = "s" + (sessionNodeIds.size() + 1);
            sessionNodeIds.put(session.id(), id);
            String label = text.clean(session.source() + " - " + session.title(), ContinuityText.LABEL_CHARS);
            nodes.add(new Node(
                    id,
                    BraidGraph.SESSION,
                    text.clean(session.id(), ContinuityText.ID_CHARS),
                    label,
                    session.startedAt().toString(),
                    projectLabel(project),
                    List.of()));
        }

        void captures(List<ContinuityCapture> captures) {
            List<ContinuityCapture> chronological = captures.stream()
                    .sorted(Comparator.comparing(ContinuityCapture::observedAt)
                            .thenComparing(ContinuityCapture::eventId))
                    .toList();
            Map<String, String> decisionNodeIds = new LinkedHashMap<>();
            for (ContinuityCapture capture : chronological) {
                boolean decision = capture.is(ContinuityCapture.DECISION);
                String id = decision ? "d" + ++decisions : "h" + ++handoffs;
                String raw = decision ? capture.field("decision") : capture.field("contextSummary");
                nodes.add(new Node(
                        id,
                        decision ? BraidGraph.DECISION : BraidGraph.HANDOFF,
                        text.clean(capture.eventId(), ContinuityText.ID_CHARS),
                        text.clean(raw == null ? capture.text() : raw, ContinuityText.LABEL_CHARS),
                        capture.observedAt().toString(),
                        projectLabel(capture.project()),
                        List.of()));
                String session = sessionNodeIds.get(capture.sessionId());
                edges.add(new Edge(session, id, decision ? "decided" : "handed_off"));
                if (decision) {
                    decisionNodeIds.put(capture.eventId(), id);
                } else {
                    handoffNodeIds.put(capture.eventId(), id);
                }
            }
            carriedBy(chronological, decisionNodeIds);
        }

        /** Each decision points at the first handoff its own session captured at or after it. */
        private void carriedBy(List<ContinuityCapture> chronological, Map<String, String> decisionNodeIds) {
            Map<String, List<ContinuityCapture>> pending = new HashMap<>();
            for (ContinuityCapture capture : chronological) {
                if (capture.is(ContinuityCapture.DECISION)) {
                    pending.computeIfAbsent(capture.sessionId(), id -> new ArrayList<>())
                            .add(capture);
                    continue;
                }
                List<ContinuityCapture> waiting = pending.remove(capture.sessionId());
                if (waiting == null) {
                    continue;
                }
                String handoff = handoffNodeIds.get(capture.eventId());
                waiting.forEach(d -> edges.add(new Edge(decisionNodeIds.get(d.eventId()), handoff, "carried_by")));
            }
        }

        /** Each scoped handoff points at the earliest later-started session in the same project. */
        void pickups(List<ContinuityCapture> captures, List<SessionRow> sessions, Map<String, String> projects) {
            captures.stream()
                    .filter(c -> c.is(ContinuityCapture.HANDOFF) && c.scoped())
                    .sorted(Comparator.comparing(ContinuityCapture::observedAt)
                            .thenComparing(ContinuityCapture::eventId))
                    .forEach(handoff -> sessions.stream()
                            .filter(s -> !s.id().equals(handoff.sessionId()))
                            .filter(s -> handoff.project().equals(projects.get(s.id())))
                            .filter(s -> s.startedAt().isAfter(handoff.observedAt()))
                            .findFirst()
                            .ifPresent(next -> edges.add(new Edge(
                                    handoffNodeIds.get(handoff.eventId()),
                                    sessionNodeIds.get(next.id()),
                                    "picked_up_by"))));
        }

        void braids(List<BraidRow> rows, Set<String> keptSessions) {
            List<BraidRow> chronological = rows.stream()
                    .sorted(Comparator.comparing(BraidRow::createdAt).thenComparing(BraidRow::id))
                    .toList();
            for (BraidRow braid : chronological) {
                String id = "b" + ++braids;
                nodes.add(new Node(
                        id,
                        BraidGraph.BRAID,
                        text.clean(braid.id(), ContinuityText.ID_CHARS),
                        text.clean(braid.title(), ContinuityText.LABEL_CHARS),
                        braid.createdAt().toString(),
                        text.clean(braid.projectKey(), ContinuityText.PATH_CHARS),
                        flags(braid)));
                for (BraidInput input : braid.inputs()) {
                    String from =
                            input.sessionExists() ? sessionNodeIds.get(input.sessionId()) : missing(input.sessionId());
                    if (from != null && (!input.sessionExists() || keptSessions.contains(input.sessionId()))) {
                        edges.add(new Edge(from, id, "woven_into"));
                    }
                }
            }
        }

        private String missing(String sessionId) {

            return missingNodeIds.computeIfAbsent(sessionId, ref -> {
                String id = "x" + (missingNodeIds.size() + 1);
                nodes.add(new Node(
                        id,
                        BraidGraph.MISSING_SESSION,
                        text.clean(ref, ContinuityText.ID_CHARS),
                        "missing session",
                        null,
                        null,
                        List.of("missing")));

                return id;
            });
        }

        private static List<String> flags(BraidRow braid) {
            int inputs = braid.inputs().size();
            long missing =
                    braid.inputs().stream().filter(i -> !i.sessionExists()).count();
            Set<String> flags = new TreeSet<>();
            if (inputs == 0 || missing == inputs) {
                flags.add("orphan");
            } else if (missing > 0) {
                flags.add("missing_sessions");
            }
            if (inputs > 0 && inputs < 2) {
                flags.add("undersized");
            }

            return List.copyOf(flags);
        }

        private String projectLabel(String project) {
            boolean unscoped = project == null || ProjectKeyCodec.NO_PROJECT_KEY.equals(project);

            return unscoped ? null : text.clean(project, ContinuityText.PATH_CHARS);
        }

        Counts counts() {

            return new Counts(sessionNodeIds.size(), decisions, handoffs, braids, missingNodeIds.size(), edges.size());
        }
    }
}
