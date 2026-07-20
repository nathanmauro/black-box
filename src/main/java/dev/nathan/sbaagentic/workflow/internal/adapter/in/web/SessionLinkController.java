package dev.nathan.sbaagentic.workflow.internal.adapter.in.web;

import dev.nathan.sbaagentic.workflow.CreateSessionLinkRequest;
import dev.nathan.sbaagentic.workflow.SessionLineageOperations;
import dev.nathan.sbaagentic.workflow.SessionLink;
import dev.nathan.sbaagentic.workflow.SessionLinksResponse;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class SessionLinkController {

    private final SessionLineageOperations sessionLinks;

    public SessionLinkController(SessionLineageOperations sessionLinks) {
        this.sessionLinks = sessionLinks;
    }

    @PostMapping("/session-links")
    public SessionLink createSessionLink(@RequestBody CreateSessionLinkRequest request) {
        return sessionLinks.createLink(request);
    }

    @GetMapping("/sessions/{sessionId}/links")
    public SessionLinksResponse sessionLinks(@PathVariable String sessionId) {
        return sessionLinks.linksForSession(sessionId);
    }
}
