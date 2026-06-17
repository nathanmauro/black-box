package dev.nathan.sbaagentic.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Forwards the single-page app's client routes to {@code index.html} so deep links and hard
 * refreshes (e.g. {@code /sessions/<id>}, {@code /search}) resolve instead of 404ing. The route list
 * is explicit — never a catch-all — so {@code /api/**} and hashed static assets are never shadowed.
 * Add phase-2 client routes (Recall, Projects) here when those views land.
 */
@Controller
public class SpaForwardingController {

    @GetMapping(value = {"/overview", "/sessions", "/sessions/**", "/search", "/recall", "/projects", "/projects/**"})
    public String forward() {
        return "forward:/index.html";
    }
}
