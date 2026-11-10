package dev.nathan.sbaagentic.lineage.internal.application;

import dev.nathan.sbaagentic.lineage.DagEdge;
import dev.nathan.sbaagentic.lineage.DagNode;
import dev.nathan.sbaagentic.lineage.DagOperations;
import dev.nathan.sbaagentic.lineage.DagResponse;
import dev.nathan.sbaagentic.lineage.SessionLineageOperations;
import dev.nathan.sbaagentic.lineage.SessionLink;
import dev.nathan.sbaagentic.recording.RecordingCatalog;
import dev.nathan.sbaagentic.recording.SessionHeader;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
        List<SessionLink> childLinks = sessionLinks.linksWhereParent(sessionId);
        List<SessionLink> parentLinks = sessionLinks.linksWhereChild(sessionId);
        LinkedHashSet<String> sessionIds = new LinkedHashSet<>();
        sessionIds.add(sessionId);
        childLinks.forEach(link -> sessionIds.add(link.childSessionId()));
        parentLinks.forEach(link -> sessionIds.add(link.parentSessionId()));
        Map<String, SessionHeader> headers = eventRepository.sessionHeaders(List.copyOf(sessionIds));
        LinkedHashMap<String, DagNode> nodes = new LinkedHashMap<>();
        for (String id : sessionIds) {
            nodes.put(sessionNodeId(id), hydrateSessionNode(id, headers));
        }
        LinkedHashSet<DagEdge> edges = new LinkedHashSet<>();
        String sessionNodeId = sessionNodeId(sessionId);
        for (SessionLink link : childLinks) {
            String childSessionNodeId = sessionNodeId(link.childSessionId());
            edges.add(new DagEdge(
                    sessionNodeId, childSessionNodeId, link.linkType().value()));
        }
        for (SessionLink link : parentLinks) {
            String parentSessionNodeId = sessionNodeId(link.parentSessionId());
            edges.add(new DagEdge(
                    parentSessionNodeId, sessionNodeId, link.linkType().value()));
        }

        return new DagResponse(List.copyOf(nodes.values()), List.copyOf(edges));
    }

    private static String sessionNodeId(String sessionId) {

        return "session:" + sessionId;
    }

    private DagNode hydrateSessionNode(String sessionId, Map<String, SessionHeader> headers) {
        SessionHeader session = headers.get(sessionId);
        String label = session == null || session.title() == null ? sessionId : session.title();

        return new DagNode(sessionNodeId(sessionId), "session", label, null, sessionId);
    }
}
