package dev.nathan.sbaagentic.memory.internal.domain;

import static dev.nathan.sbaagentic.memory.internal.domain.ContinuationText.collapse;
import static dev.nathan.sbaagentic.memory.internal.domain.ContinuationText.jaccard;
import static dev.nathan.sbaagentic.memory.internal.domain.ContinuationText.noAction;
import static dev.nathan.sbaagentic.memory.internal.domain.ContinuationText.normalized;

import dev.nathan.sbaagentic.memory.internal.domain.ContinuationCapture.Kind;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Deterministic frontier and ranking for continuation candidates, ported from the Projects
 * trajectory view ({@code frontend/src/lib/trajectory.ts}): captures group into bursts split by gaps
 * over 48 hours; the head is the latest handoff in the newest burst, else that burst's newest
 * capture; the frontier keeps each session's newest handoff in the head burst or within seven days
 * of the head. Nothing here infers that a recorded item was resolved.
 */
public final class ContinuationRanker {

    public static final Duration BURST_GAP = Duration.ofHours(48);
    public static final Duration FRONTIER_WINDOW = Duration.ofDays(7);
    public static final Duration STALE_AFTER = Duration.ofDays(14);
    public static final int MAX_NEXT_ACTIONS = 2;
    public static final double DUPLICATE_SIMILARITY = 0.5;

    private static final Comparator<Timed> NEWEST_FIRST = Comparator.comparing(Timed::at)
            .thenComparing(timed -> timed.capture().eventId())
            .reversed();

    private ContinuationRanker() {}

    public static ContinuationFrontier rank(
            String root, List<ContinuationCapture> handoffs, List<ContinuationCapture> decisions, Instant now) {
        List<Timed> timeline = timeline(handoffs, decisions, now);
        if (timeline.isEmpty()) {

            return new ContinuationFrontier(now, null, null, false, false, 0, List.of(), List.of(), List.of());
        }

        List<Timed> headBurst = newestBurst(timeline);
        Timed head = headBurst.getLast();
        for (int index = headBurst.size() - 1; index >= 0; index--) {
            if (headBurst.get(index).capture().kind() == Kind.HANDOFF) {
                head = headBurst.get(index);
                break;
            }
        }
        Instant headAt = head.at();
        boolean stale = Duration.between(headAt, now).compareTo(STALE_AFTER) > 0;

        List<Timed> frontier = frontierHandoffs(timeline, headBurst, headAt);
        List<Timed> decisionsNewestFirst = timeline.stream()
                .filter(timed -> timed.capture().kind() == Kind.DECISION)
                .sorted(NEWEST_FIRST)
                .toList();
        List<Group> groups = new ArrayList<>();
        int nextActionGroups = 0;
        for (Timed handoff : frontier) {
            String text = collapse(handoff.capture().nextAction());
            if (!usable(text)) {
                continue;
            }
            ContinuationSource source = handoff.source(ContinuationBasis.HANDOFF_NEXT_ACTION, text);
            Group match = matching(groups, text);
            if (match != null) {
                match.add(source);
            } else if (nextActionGroups < MAX_NEXT_ACTIONS) {
                groups.add(new Group(source));
                nextActionGroups++;
            }
        }
        for (Timed handoff : frontier) {
            for (String loop : handoff.capture().openLoops()) {
                addLoop(groups, handoff, ContinuationBasis.HANDOFF_OPEN_LOOP, loop);
            }
        }
        for (Timed decision : decisionsNewestFirst) {
            if (near(decision.at(), headAt)) {
                for (String loop : decision.capture().openLoops()) {
                    addLoop(groups, decision, ContinuationBasis.DECISION_OPEN_LOOP, loop);
                }
            }
        }

        List<ContinuationCandidate> unranked =
                groups.stream().map(group -> group.candidate(root, now)).toList();
        List<ContinuationCandidate> ordered = unranked.stream()
                .sorted(Comparator.comparingInt((ContinuationCandidate candidate) ->
                                candidate.basis().tier())
                        .thenComparing(ContinuationCandidate::recordedAt, Comparator.reverseOrder())
                        .thenComparing(
                                candidate ->
                                        candidate.sources().getFirst().capture().eventId(),
                                Comparator.reverseOrder()))
                .toList();
        List<ContinuationCandidate> ranked = new ArrayList<>(ordered.size());
        for (int index = 0; index < ordered.size(); index++) {
            ContinuationCandidate candidate = ordered.get(index);
            ranked.add(new ContinuationCandidate(
                    candidate.id(),
                    index + 1,
                    candidate.basis(),
                    candidate.text(),
                    candidate.recordedAt(),
                    candidate.ageDays(),
                    candidate.stale(),
                    candidate.primary(),
                    candidate.sources()));
        }

        return new ContinuationFrontier(
                now,
                headAt,
                timeline.getLast().at(),
                stale,
                timeline.stream().anyMatch(Timed::future),
                timeline.size(),
                frontier.stream().map(Timed::capture).toList(),
                decisionsNewestFirst.stream().map(Timed::capture).toList(),
                ranked);
    }

    private static List<Timed> timeline(
            List<ContinuationCapture> handoffs, List<ContinuationCapture> decisions, Instant now) {
        List<Timed> timeline = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Stream.concat(
                        handoffs == null ? Stream.empty() : handoffs.stream(),
                        decisions == null ? Stream.empty() : decisions.stream())
                .forEach(capture -> {
                    if (capture == null
                            || capture.eventId() == null
                            || capture.kind() == null
                            || capture.observedAt() == null
                            || !seen.add(capture.eventId())) {

                        return;
                    }
                    boolean future = capture.observedAt().isAfter(now);
                    timeline.add(new Timed(capture, future ? now : capture.observedAt(), future));
                });
        timeline.sort(Comparator.comparing(Timed::at)
                .thenComparing(timed -> timed.capture().eventId()));

        return timeline;
    }

    private static List<Timed> newestBurst(List<Timed> timeline) {
        List<Timed> burst = new ArrayList<>();
        Instant end = null;
        for (Timed timed : timeline) {
            if (end != null && Duration.between(end, timed.at()).compareTo(BURST_GAP) > 0) {
                burst = new ArrayList<>();
            }
            burst.add(timed);
            end = timed.at();
        }

        return burst;
    }

    private static List<Timed> frontierHandoffs(List<Timed> timeline, List<Timed> headBurst, Instant headAt) {
        Set<String> headBurstIds = new HashSet<>();
        headBurst.forEach(timed -> headBurstIds.add(timed.capture().eventId()));
        Map<String, Timed> newestBySession = new LinkedHashMap<>();
        for (Timed timed : timeline) {
            ContinuationCapture capture = timed.capture();
            if (capture.kind() != Kind.HANDOFF || capture.sessionId() == null) {
                continue;
            }
            if (!headBurstIds.contains(capture.eventId()) && !near(timed.at(), headAt)) {
                continue;
            }
            Timed existing = newestBySession.get(capture.sessionId());
            if (existing == null || !timed.at().isBefore(existing.at())) {
                newestBySession.put(capture.sessionId(), timed);
            }
        }

        return newestBySession.values().stream().sorted(NEWEST_FIRST).toList();
    }

    private static void addLoop(List<Group> groups, Timed owner, ContinuationBasis basis, String loop) {
        String text = collapse(loop);
        if (!usable(text)) {

            return;
        }
        ContinuationSource source = owner.source(basis, text);
        Group match = matching(groups, text);
        if (match != null) {
            match.add(source);
        } else {
            groups.add(new Group(source));
        }
    }

    /** Exact normalized text first, then token Jaccard at or above the trajectory threshold. */
    private static Group matching(List<Group> groups, String text) {
        String normalized = normalized(text);
        for (Group group : groups) {
            if (group.normalizedTexts.contains(normalized)) {

                return group;
            }
        }
        for (Group group : groups) {
            for (ContinuationSource source : group.sources) {
                if (jaccard(source.text(), text) >= DUPLICATE_SIMILARITY) {

                    return group;
                }
            }
        }

        return null;
    }

    private static boolean usable(String collapsed) {

        return !collapsed.isEmpty() && !noAction(collapsed);
    }

    private static boolean near(Instant at, Instant headAt) {

        return Duration.between(at, headAt).abs().compareTo(FRONTIER_WINDOW) <= 0;
    }

    private record Timed(ContinuationCapture capture, Instant at, boolean future) {

        ContinuationSource source(ContinuationBasis basis, String text) {

            return new ContinuationSource(capture, basis, text, at, future);
        }
    }

    private static final class Group {
        private final List<ContinuationSource> sources = new ArrayList<>();
        private final Set<String> normalizedTexts = new LinkedHashSet<>();
        private final Set<String> fields = new HashSet<>();

        Group(ContinuationSource first) {
            add(first);
        }

        void add(ContinuationSource source) {
            normalizedTexts.add(normalized(source.text()));
            if (fields.add(source.capture().eventId() + "\n" + source.field())) {
                sources.add(source);
            }
        }

        ContinuationCandidate candidate(String root, Instant now) {
            List<ContinuationSource> newestFirst = sources.stream()
                    .sorted(Comparator.comparing(ContinuationSource::effectiveAt, Comparator.reverseOrder())
                            .thenComparingInt(source -> source.basis().tier())
                            .thenComparing(source -> source.capture().eventId(), Comparator.reverseOrder()))
                    .toList();
            ContinuationSource newest = newestFirst.getFirst();
            ContinuationSource primary = newestFirst.stream()
                    .min(Comparator.comparingInt((ContinuationSource source) ->
                                    source.basis().tier())
                            .thenComparing(ContinuationSource::effectiveAt, Comparator.reverseOrder())
                            .thenComparing(source -> source.capture().eventId(), Comparator.reverseOrder()))
                    .orElse(newest);
            Instant recordedAt = newest.effectiveAt();

            return new ContinuationCandidate(
                    ContinuationText.candidateId(root, newest.text()),
                    0,
                    primary.basis(),
                    newest.text(),
                    recordedAt,
                    ContinuationText.ageDays(recordedAt, now),
                    Duration.between(recordedAt, now).compareTo(STALE_AFTER) > 0,
                    primary,
                    newestFirst);
        }
    }
}
