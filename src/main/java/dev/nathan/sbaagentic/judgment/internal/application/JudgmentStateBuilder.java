package dev.nathan.sbaagentic.judgment.internal.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import dev.nathan.sbaagentic.judgment.JudgmentProperties;
import dev.nathan.sbaagentic.judgment.internal.domain.Beat;
import dev.nathan.sbaagentic.judgment.internal.domain.BeatEvent;
import dev.nathan.sbaagentic.judgment.internal.domain.BeatState;
import dev.nathan.sbaagentic.recording.AgentSession;
import dev.nathan.sbaagentic.recording.EventTypes;
import dev.nathan.sbaagentic.recording.RecordingCatalog;
import dev.nathan.sbaagentic.workflow.SessionLineageOperations;
import dev.nathan.sbaagentic.workflow.SessionLink;

public class JudgmentStateBuilder {

    private static final Duration LIVE_WINDOW = Duration.ofMinutes(30);
    private static final Set<String> PROMPT_TYPES = Set.of(
            "userpromptsubmit",
            "beforesubmitprompt",
            "manualcapture",
            "quicknote");
    private static final List<String> RELAYED_PREFIXES = List.of(
            "<agent-message",
            "<system-reminder",
            "<task-notification",
            "<local-command",
            "<command-name");

    private final RecordingCatalog recording;
    private final SessionLineageOperations lineage;
    private final JudgmentProperties properties;
    private final Clock clock;

    public JudgmentStateBuilder(
            RecordingCatalog recording,
            SessionLineageOperations lineage,
            JudgmentProperties properties,
            Clock clock) {
        this.recording = recording;
        this.lineage = lineage;
        this.properties = properties;
        this.clock = clock;
    }

    public Optional<BeatState> build(Beat beat, Map<String, ArrayDeque<String>> trails) {
        Optional<AgentSession> session = recording.findSessionById(beat.sessionId());
        if (session.isEmpty()) {
            return Optional.empty();
        }
        AgentSession current = session.get();
        List<String> trail = trailFor(current.id(), trails);
        List<BeatState.OtherSessionState> others = othersFor(current, trails);
        boolean askHuman = askHuman(beat);
        return Optional.of(new BeatState(
                beat,
                new BeatState.SessionState(current.source(), current.cwd(), current.title()),
                trail,
                others,
                askHuman,
                askHuman ? Double.NaN : 0.0));
    }

    private List<String> trailFor(String sessionId, Map<String, ArrayDeque<String>> trails) {
        ArrayDeque<String> titles = trails.get(sessionId);
        if (titles == null || titles.isEmpty()) {
            return List.of();
        }
        return titles.stream()
                .skip(Math.max(0, titles.size() - 5))
                .toList();
    }

    private List<BeatState.OtherSessionState> othersFor(
            AgentSession current,
            Map<String, ArrayDeque<String>> trails) {
        Instant liveCutoff = clock.instant().minus(LIVE_WINDOW);
        List<AgentSession> liveSessions = recording.recentSessions(100, true).stream()
                .filter(session -> session.lastSeenAt() != null && !session.lastSeenAt().isBefore(liveCutoff))
                .toList();
        Set<String> excluded = relatedSessionIds(current, liveSessions);
        excluded.add(current.id());
        List<BeatState.OtherSessionState> others = new ArrayList<>();
        int k = 0;
        int maxOthers = Math.max(0, properties.getMaxOthers());
        for (AgentSession session : liveSessions) {
            if (k >= maxOthers) {
                break;
            }
            if (excluded.contains(session.id())) {
                continue;
            }
            String latest = latestTitles(session.id(), trails);
            if (latest.isBlank()) {
                continue;
            }
            others.add(new BeatState.OtherSessionState(
                    session.id(), k, session.source(), session.cwd(), session.title(), latest));
            k++;
        }
        return others;
    }

    private Set<String> relatedSessionIds(AgentSession current, List<AgentSession> liveSessions) {
        Set<String> related = new LinkedHashSet<>();
        Set<String> parents = directParents(current);
        related.addAll(parents);
        for (String parent : parents) {
            collectAncestors(parent, related);
            collectDescendants(parent, related);
        }
        collectDescendants(current.id(), related);
        for (AgentSession session : liveSessions) {
            if (session.spawnedBy() != null
                    && (session.spawnedBy().equals(current.clientSessionId())
                            || session.spawnedBy().equals(current.spawnedBy()))) {
                related.add(session.id());
            }
        }
        return related;
    }

    private Set<String> directParents(AgentSession session) {
        Set<String> parents = new LinkedHashSet<>();
        for (SessionLink link : safeLinksWhereChild(session.id())) {
            parents.add(link.parentSessionId());
        }
        if (session.spawnedBy() != null && !session.spawnedBy().isBlank()) {
            recording.findSession(session.source(), session.spawnedBy())
                    .map(AgentSession::id)
                    .ifPresent(parents::add);
        }
        return parents;
    }

    private void collectAncestors(String sessionId, Set<String> seen) {
        for (SessionLink link : safeLinksWhereChild(sessionId)) {
            if (seen.add(link.parentSessionId())) {
                collectAncestors(link.parentSessionId(), seen);
            }
        }
    }

    private void collectDescendants(String sessionId, Set<String> seen) {
        for (SessionLink link : safeLinksWhereParent(sessionId)) {
            if (seen.add(link.childSessionId())) {
                collectDescendants(link.childSessionId(), seen);
            }
        }
    }

    private List<SessionLink> safeLinksWhereChild(String sessionId) {
        try {
            return lineage.linksWhereChild(sessionId);
        }
        catch (RuntimeException ex) {
            return List.of();
        }
    }

    private List<SessionLink> safeLinksWhereParent(String sessionId) {
        try {
            return lineage.linksWhereParent(sessionId);
        }
        catch (RuntimeException ex) {
            return List.of();
        }
    }

    private static String latestTitles(String sessionId, Map<String, ArrayDeque<String>> trails) {
        ArrayDeque<String> titles = trails.get(sessionId);
        if (titles == null || titles.isEmpty()) {
            return "";
        }
        return String.join("\n", titles.stream()
                .skip(Math.max(0, titles.size() - 3))
                .toList());
    }

    private static boolean askHuman(Beat beat) {
        boolean promptLike = false;
        for (BeatEvent event : beat.events()) {
            if (!promptLike(event)) {
                continue;
            }
            promptLike = true;
            if (!relayed(event.text())) {
                return true;
            }
        }
        return false;
    }

    private static boolean promptLike(BeatEvent event) {
        String type = EventTypes.normalize(event.type());
        return PROMPT_TYPES.contains(type) || "user".equalsIgnoreCase(event.role());
    }

    private static boolean relayed(String text) {
        String value = text == null ? "" : text.trim().toLowerCase(java.util.Locale.ROOT);
        for (String prefix : RELAYED_PREFIXES) {
            if (value.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
