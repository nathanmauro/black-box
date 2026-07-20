package dev.nathan.sbaagentic.workflow.internal.adapter.in.web;

import dev.nathan.sbaagentic.link.CreateSessionLinkRequest;
import dev.nathan.sbaagentic.link.SessionLink;
import dev.nathan.sbaagentic.link.SessionLinkService;
import dev.nathan.sbaagentic.link.SessionLinksResponse;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class SessionLinkController {

    private final SessionLinkService sessionLinkService;

    public SessionLinkController(SessionLinkService sessionLinkService) {
        this.sessionLinkService = sessionLinkService;
    }

    @PostMapping("/session-links")
    public SessionLink createSessionLink(@RequestBody CreateSessionLinkRequest request) {
        return sessionLinkService.createLink(request);
    }

    @GetMapping("/sessions/{sessionId}/links")
    public SessionLinksResponse sessionLinks(@PathVariable String sessionId) {
        return sessionLinkService.linksForSession(sessionId);
    }
}
