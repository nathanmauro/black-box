package dev.nathan.sbaagentic.memory.internal.adapter.in.web;

import dev.nathan.sbaagentic.memory.RecallRequestContext;
import dev.nathan.sbaagentic.memory.internal.application.ContinuationService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only continuation prompts: built per request from exact project recall, never stored. */
@RestController
@RequestMapping("/api/continuations")
public class ContinuationController {

    private final ContinuationService continuations;

    public ContinuationController(ContinuationService continuations) {
        this.continuations = continuations;
    }

    @GetMapping
    public ContinuationResponse continuations(
            @RequestParam(required = false) String project,
            @RequestParam(required = false) Integer withinHours,
            @RequestParam(required = false) Integer max,
            @RequestHeader(value = "X-Blackbox-Client", required = false) String client,
            @RequestHeader(value = "X-Blackbox-Project", required = false) String telemetryProject,
            HttpServletResponse response) {
        try (var context = RecallRequestContext.open("http", client, "continuation", telemetryProject)) {
            response.setHeader("X-Blackbox-Recall-Id", context.requestId());

            return ContinuationResponse.from(continuations.build(project, withinHours, max));
        }
    }
}
