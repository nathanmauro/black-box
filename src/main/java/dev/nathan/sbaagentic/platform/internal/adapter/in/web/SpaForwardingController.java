package dev.nathan.sbaagentic.platform.internal.adapter.in.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Forwards the single-page app's client routes to {@code index.html} so deep links and hard
 * refreshes (e.g. {@code /sessions/<id>}, {@code /search}) resolve instead of 404ing. The route list
 * is explicit — never a catch-all — so {@code /api/**} and hashed static assets are never shadowed.
 * {@code /stream} is exact — it never catches the {@code /api/stream} SSE endpoint, and {@code /ideas}
 * is exact — it never catches {@code /api/ideas}.
 */
@Controller
public class SpaForwardingController {

    @GetMapping(
            value = {
                "/stream",
                "/sessions",
                "/sessions/**",
                "/search",
                "/recall",
                "/projects",
                "/projects/**",
                "/graph",
                "/ideas",
                "/companion"
            })
    public String forward() {

        return "forward:/index.html";
    }
}
