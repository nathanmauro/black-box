package dev.nathan.sbaagentic.lineage.internal.application;

import dev.nathan.sbaagentic.lineage.CreateSessionLinkRequest;
import dev.nathan.sbaagentic.lineage.LinkDomainException;
import dev.nathan.sbaagentic.lineage.LinkErrorCode;
import dev.nathan.sbaagentic.lineage.LinkType;
import dev.nathan.sbaagentic.lineage.SessionLineageOperations;
import dev.nathan.sbaagentic.lineage.SessionLink;
import dev.nathan.sbaagentic.lineage.SessionLinkView;
import dev.nathan.sbaagentic.lineage.SessionLinksResponse;
import dev.nathan.sbaagentic.lineage.SessionRef;
import dev.nathan.sbaagentic.lineage.internal.application.port.SessionLinkStore;
import dev.nathan.sbaagentic.recording.RecordingCatalog;
import dev.nathan.sbaagentic.recording.SessionHeader;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
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
        } catch (IllegalArgumentException ex) {
            throw new LinkDomainException(LinkErrorCode.VALIDATION_FAILED, "Unknown link type: " + request.linkType());
        }

        try {

            return repository.createLink(request.parentSessionId(), request.childSessionId(), linkType);
        } catch (DataIntegrityViolationException ex) {
            throw new LinkDomainException(
                    LinkErrorCode.DUPLICATE_LINK,
                    "Link already exists for " + request.parentSessionId() + " -> " + request.childSessionId() + " ("
                            + request.linkType() + ")");
        }
    }

    public SessionLinksResponse linksForSession(String sessionId) {
        requireText(sessionId, "Session id");
        List<SessionLink> parentLinks = repository.linksWhereChild(sessionId);
        List<SessionLink> childLinks = repository.linksWhereParent(sessionId);
        List<String> peers = Stream.concat(
                        parentLinks.stream().map(SessionLink::parentSessionId),
                        childLinks.stream().map(SessionLink::childSessionId))
                .distinct()
                .toList();
        Map<String, SessionHeader> headers = eventRepository.sessionHeaders(peers);
        List<SessionLinkView> parents = parentLinks.stream()
                .map(link -> view(link, link.parentSessionId(), headers))
                .toList();
        List<SessionLinkView> children = childLinks.stream()
                .map(link -> view(link, link.childSessionId(), headers))
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

    private SessionLinkView view(SessionLink link, String otherSessionId, Map<String, SessionHeader> headers) {
        SessionHeader other = headers.get(otherSessionId);
        SessionRef session = other == null
                ? new SessionRef(otherSessionId, null, null)
                : new SessionRef(other.id(), other.title(), other.source());

        return new SessionLinkView(
                link.id(), link.parentSessionId(), link.childSessionId(), link.linkType(), link.createdAt(), session);
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
