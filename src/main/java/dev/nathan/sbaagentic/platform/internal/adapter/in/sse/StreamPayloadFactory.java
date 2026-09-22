package dev.nathan.sbaagentic.platform.internal.adapter.in.sse;

import dev.nathan.sbaagentic.platform.internal.application.StreamEventSnapshot;
import dev.nathan.sbaagentic.recording.AgentEvent;
import dev.nathan.sbaagentic.recording.AgentSession;
import dev.nathan.sbaagentic.recording.RecordingCatalog;
import dev.nathan.sbaagentic.workflow.SessionLineageOperations;
import dev.nathan.sbaagentic.workflow.SessionLink;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class StreamPayloadFactory {

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private final RecordingCatalog recording;
    private final SessionLineageOperations lineage;

    public StreamPayloadFactory(RecordingCatalog recording, SessionLineageOperations lineage) {
        this.recording = recording;
        this.lineage = lineage;
    }

    public StreamEvents.EventAppended eventAppended(AgentSession session, AgentEvent event) {

        return new StreamEvents.EventAppended(
                event.sessionId(),
                event.source(),
                event.eventType(),
                event.toolName(),
                session.title(),
                event.observedAt() == null ? null : event.observedAt().toString(),
                event.id(),
                session.cwd(),
                event.role(),
                preview(event.text()),
                parentSessionIdFromSpawnedBy(session.source(), session.spawnedBy()));
    }

    public StreamEvents.EventAppended eventAppended(StreamEventSnapshot snapshot) {

        return new StreamEvents.EventAppended(
                snapshot.sessionId(),
                snapshot.source(),
                snapshot.eventType(),
                snapshot.toolName(),
                snapshot.title(),
                snapshot.observedAt() == null ? null : snapshot.observedAt().toString(),
                snapshot.id(),
                snapshot.cwd(),
                snapshot.role(),
                preview(snapshot.text()),
                parentSessionId(snapshot.sessionId(), snapshot.source(), snapshot.spawnedBy()));
    }

    public StreamEvents.SessionUpdated sessionUpdated(AgentSession session) {

        return new StreamEvents.SessionUpdated(
                session.id(),
                session.source(),
                session.title(),
                session.cwd(),
                session.eventCount(),
                session.lastSeenAt() == null ? null : session.lastSeenAt().toString(),
                parentSessionId(session),
                linkTypes(session.id()));
    }

    private String parentSessionId(AgentSession session) {

        return parentSessionId(session.id(), session.source(), session.spawnedBy());
    }

    private String parentSessionId(String sessionId, String source, String spawnedBy) {
        String parentFromSpawnedBy = parentSessionIdFromSpawnedBy(source, spawnedBy);
        if (parentFromSpawnedBy != null) {

            return parentFromSpawnedBy;
        }

        return safeLinksWhereChild(sessionId).stream()
                .findFirst()
                .map(SessionLink::parentSessionId)
                .orElse(null);
    }

    private String parentSessionIdFromSpawnedBy(String source, String spawnedBy) {
        if (spawnedBy != null && !spawnedBy.isBlank()) {
            Optional<AgentSession> parent = recording.findSession(source, spawnedBy);
            if (parent.isPresent()) {

                return parent.get().id();
            }
        }

        return null;
    }

    private List<String> linkTypes(String sessionId) {
        Set<String> types = new LinkedHashSet<>();
        for (SessionLink link : safeLinksWhereChild(sessionId)) {
            types.add(link.linkType().value());
        }

        return List.copyOf(types);
    }

    private List<SessionLink> safeLinksWhereChild(String sessionId) {
        try {

            return lineage.linksWhereChild(sessionId);
        } catch (RuntimeException ex) {

            return List.of();
        }
    }

    private static String preview(String text) {
        if (text == null || text.isBlank()) {

            return null;
        }
        String collapsed = WHITESPACE.matcher(text.trim()).replaceAll(" ");

        return collapsed.length() <= 240 ? collapsed : collapsed.substring(0, 240);
    }
}
