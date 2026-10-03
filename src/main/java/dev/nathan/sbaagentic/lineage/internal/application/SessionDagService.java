package dev.nathan.sbaagentic.lineage.internal.application;

import dev.nathan.sbaagentic.lineage.DagEdge;
import dev.nathan.sbaagentic.lineage.DagNode;
import dev.nathan.sbaagentic.lineage.DagOperations;
import dev.nathan.sbaagentic.lineage.DagResponse;
import dev.nathan.sbaagentic.lineage.SessionLineageOperations;
import dev.nathan.sbaagentic.lineage.SessionLink;
import dev.nathan.sbaagentic.recording.AgentSession;
import dev.nathan.sbaagentic.recording.RecordingCatalog;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class SessionDagService implements DagOperations {
    private final SessionLineageOperations sessionLinks;
    private final RecordingCatalog eventRepository;

    public SessionDagService(SessionLineageOperations sessionLinks, RecordingCatalog eventRepository) {
        this.sessionLinks = sessionLinks;
        this.eventRepository = eventRepository;
    }

    public DagResponse forSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("Session id is required");
        }
        LinkedHashMap<String, DagNode> nodes = new LinkedHashMap<>();
        LinkedHashSet<DagEdge> edges = new LinkedHashSet<>();
        String sessionNodeId = sessionNodeId(sessionId);
        nodes.put(sessionNodeId, hydrateSessionNode(sessionId));
        for (SessionLink link : sessionLinks.linksWhereParent(sessionId)) {
            String childSessionNodeId = sessionNodeId(link.childSessionId());
            nodes.putIfAbsent(childSessionNodeId, hydrateSessionNode(link.childSessionId()));
            edges.add(new DagEdge(
                    sessionNodeId, childSessionNodeId, link.linkType().value()));
        }
        for (SessionLink link : sessionLinks.linksWhereChild(sessionId)) {
            String parentSessionNodeId = sessionNodeId(link.parentSessionId());
            nodes.putIfAbsent(parentSessionNodeId, hydrateSessionNode(link.parentSessionId()));
            edges.add(new DagEdge(
                    parentSessionNodeId, sessionNodeId, link.linkType().value()));
        }

        return new DagResponse(List.copyOf(nodes.values()), List.copyOf(edges));
    }

    private static String sessionNodeId(String sessionId) {

        return "session:" + sessionId;
    }

    private DagNode hydrateSessionNode(String sessionId) {
        String label = eventRepository
                .findSessionById(sessionId)
                .map(AgentSession::title)
                .orElse(sessionId);

        return new DagNode(sessionNodeId(sessionId), "session", label, null, sessionId);
    }
}
