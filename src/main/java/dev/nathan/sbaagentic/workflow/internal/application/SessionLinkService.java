package dev.nathan.sbaagentic.workflow.internal.application;

import java.util.List;
import java.util.Map;

import dev.nathan.sbaagentic.recording.RecordingCatalog;
import dev.nathan.sbaagentic.workflow.CreateSessionLinkRequest;
import dev.nathan.sbaagentic.workflow.LinkDomainException;
import dev.nathan.sbaagentic.workflow.LinkErrorCode;
import dev.nathan.sbaagentic.workflow.LinkType;
import dev.nathan.sbaagentic.workflow.SessionLineageOperations;
import dev.nathan.sbaagentic.workflow.SessionLink;
import dev.nathan.sbaagentic.workflow.SessionLinksResponse;
import dev.nathan.sbaagentic.workflow.SessionLinkView;
import dev.nathan.sbaagentic.workflow.SessionRef;
import dev.nathan.sbaagentic.workflow.internal.application.port.SessionLinkStore;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
public class SessionLinkService implements SessionLineageOperations {

    private final SessionLinkStore repository;
    private final RecordingCatalog eventRepository;

    public SessionLinkService(SessionLinkStore repository, RecordingCatalog eventRepository) {
        this.repository = repository;
        this.eventRepository = eventRepository;
    }

    public SessionLink createLink(CreateSessionLinkRequest request) {
        requireRequest(request, "Session link request");
        requireText(request.parentSessionId(), "Parent session id");
        requireText(request.childSessionId(), "Child session id");
        requireText(request.linkType(), "Link type");

        LinkType linkType;
        try {
            linkType = LinkType.fromValue(request.linkType());
        }
        catch (IllegalArgumentException ex) {
            throw new LinkDomainException(
                    LinkErrorCode.VALIDATION_FAILED,
                    "Unknown link type: " + request.linkType());
        }

        try {
            return repository.createLink(
                    request.parentSessionId(),
                    request.childSessionId(),
                    linkType,
                    request.taskId());
        }
        catch (DataIntegrityViolationException ex) {
            throw new LinkDomainException(
                    LinkErrorCode.DUPLICATE_LINK,
                    "Link already exists for " + request.parentSessionId() + " -> "
                            + request.childSessionId() + " (" + request.linkType() + ")");
        }
    }

    public SessionLinksResponse linksForSession(String sessionId) {
        requireText(sessionId, "Session id");
        List<SessionLinkView> parents = repository.linksWhereChild(sessionId).stream()
                .map(link -> view(link, link.parentSessionId()))
                .toList();
        List<SessionLinkView> children = repository.linksWhereParent(sessionId).stream()
                .map(link -> view(link, link.childSessionId()))
                .toList();
        return new SessionLinksResponse(parents, children);
    }

    @Override
    public List<SessionLink> linksWhereParent(String sessionId) {
        return repository.linksWhereParent(sessionId);
    }

    @Override
    public List<SessionLink> linksWhereChild(String sessionId) {
        return repository.linksWhereChild(sessionId);
    }

    @Override
    public List<SessionLink> linksForTask(String taskId) {
        return repository.linksForTaskId(taskId);
    }

    @Override
    public Map<String, Long> childCounts(List<String> sessionIds) {
        if (sessionIds == null) {
            return Map.of();
        }
        List<String> ids = sessionIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return repository.childCounts(ids);
    }

    private SessionLinkView view(SessionLink link, String otherSessionId) {
        SessionRef session = eventRepository.findSessionById(otherSessionId)
                .map(other -> new SessionRef(other.id(), other.title(), other.source()))
                .orElse(new SessionRef(otherSessionId, null, null));
        return new SessionLinkView(
                link.id(),
                link.parentSessionId(),
                link.childSessionId(),
                link.linkType(),
                link.taskId(),
                link.createdAt(),
                session);
    }

    private static void requireRequest(Object request, String label) {
        if (request == null) {
            throw validation(label + " is required");
        }
    }

    private static void requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw validation(label + " is required");
        }
    }

    private static LinkDomainException validation(String message) {
        return new LinkDomainException(LinkErrorCode.VALIDATION_FAILED, message);
    }
}
