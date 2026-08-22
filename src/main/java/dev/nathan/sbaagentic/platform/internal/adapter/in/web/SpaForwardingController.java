package dev.nathan.sbaagentic.platform.internal.adapter.in.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Forwards the single-page app's client routes to {@code index.html} so deep links and hard
 * refreshes (e.g. {@code /sessions/<id>}, {@code /search}) resolve instead of 404ing. The route list
 * is explicit — never a catch-all — so {@code /api/**} and hashed static assets are never shadowed.
 * The Board is an explicit route; it never catches {@code /api/tasks} or any other API surface.
 * {@code /stream} is exact — it never catches the {@code /api/stream} SSE endpoint.
 */
@Controller
public class SpaForwardingController {

    @GetMapping(value = {"/stream", "/sessions", "/sessions/**", "/search", "/recall", "/projects", "/projects/**", "/graph", "/board"})
    public String forward() {
        return "forward:/index.html";
    }
}
