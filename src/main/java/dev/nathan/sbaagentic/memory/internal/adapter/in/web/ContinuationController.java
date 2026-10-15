package dev.nathan.sbaagentic.memory.internal.adapter.in.web;

import dev.nathan.sbaagentic.memory.RecallRequestContext;
import dev.nathan.sbaagentic.memory.internal.application.ContinuationCandidateNotFoundException;
import dev.nathan.sbaagentic.memory.internal.application.ContinuationService;
import dev.nathan.sbaagentic.memory.internal.application.ContinuationSynthesisService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Continuation prompts, built per request from exact project recall and never stored. The optional
 * restatement is a separate POST so bookmarks, Back and prefetch can never trigger a model call.
 */
@RestController
@RequestMapping("/api/continuations")
public class ContinuationController {

    private final ContinuationService continuations;
    private final ContinuationSynthesisService synthesis;

    public ContinuationController(ContinuationService continuations, ContinuationSynthesisService synthesis) {
        this.continuations = continuations;
        this.synthesis = synthesis;
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

            return ContinuationResponse.from(continuations.build(project, withinHours, max), synthesis.available());
        }
    }

    @PostMapping("/synthesize")
    public ContinuationSynthesisResponse synthesize(
            @RequestBody ContinuationSynthesisRequest request,
            @RequestHeader(value = "X-Blackbox-Client", required = false) String client,
            @RequestHeader(value = "X-Blackbox-Project", required = false) String telemetryProject,
            HttpServletResponse response) {
        try (var context = RecallRequestContext.open("http", client, "continuation", telemetryProject)) {
            response.setHeader("X-Blackbox-Recall-Id", context.requestId());

            return ContinuationSynthesisResponse.from(
                    synthesis.synthesize(request.project(), request.withinHours(), request.candidateId()));
        } catch (ContinuationCandidateNotFoundException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage());
        }
    }
}
