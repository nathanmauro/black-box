package dev.nathan.sbaagentic.memory.internal.adapter.in.web;

import java.util.List;

import dev.nathan.sbaagentic.recording.CaptureDecisionRequest;
import dev.nathan.sbaagentic.recording.CaptureHandoffRequest;
import dev.nathan.sbaagentic.recording.CaptureProjectionRequest;
import dev.nathan.sbaagentic.memory.RecallResult;
import dev.nathan.sbaagentic.memory.RecallRequestContext;
import dev.nathan.sbaagentic.memory.MemoryRecallOperations;
import dev.nathan.sbaagentic.recording.IngestResponse;
import dev.nathan.sbaagentic.recording.RecordingCaptureOperations;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class ContextController {

    private final MemoryRecallOperations contextService;
    private final RecordingCaptureOperations captureOperations;

    public ContextController(
            MemoryRecallOperations contextService,
            RecordingCaptureOperations captureOperations) {
        this.contextService = contextService;
        this.captureOperations = captureOperations;
    }

    @PostMapping("/decisions")
    public IngestResponse captureDecision(@Valid @RequestBody CaptureDecisionRequest request) {
        return captureOperations.captureDecision(request);
    }

    @PostMapping("/handoffs")
    public IngestResponse captureHandoff(@Valid @RequestBody CaptureHandoffRequest request) {
        return captureOperations.captureHandoff(request);
    }

    @PostMapping("/projections")
    public IngestResponse captureProjection(@Valid @RequestBody CaptureProjectionRequest request) {
        return captureOperations.captureProjection(request);
    }

    @GetMapping("/recall")
    public RecallResult recall(
            @RequestParam(required = false) String scope,
            @RequestParam(defaultValue = "168") int withinHours,
            @RequestParam(required = false) List<String> kinds,
            @RequestParam(required = false) Integer limit,
            @RequestHeader(value = "X-Blackbox-Client", required = false) String client,
            @RequestHeader(value = "X-Blackbox-Purpose", required = false) String purpose,
            @RequestHeader(value = "X-Blackbox-Project", required = false) String project,
            HttpServletResponse response) {
        try (var context = RecallRequestContext.open("http", client, purpose, project)) {
            response.setHeader("X-Blackbox-Recall-Id", context.requestId());
            return contextService.recall(scope, withinHours, kinds, limit);
        }
    }
}
