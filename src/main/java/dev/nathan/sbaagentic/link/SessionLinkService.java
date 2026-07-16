package dev.nathan.sbaagentic.link;

import java.util.List;

import dev.nathan.sbaagentic.event.EventRepository;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
public class SessionLinkService {

    private final SessionLinkRepository repository;
    private final EventRepository eventRepository;

    public SessionLinkService(SessionLinkRepository repository, EventRepository eventRepository) {
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
