package dev.nathan.sbaagentic.memory.internal.adapter.in.web;

import java.util.List;

import dev.nathan.sbaagentic.recording.CaptureDecisionRequest;
import dev.nathan.sbaagentic.recording.CaptureHandoffRequest;
import dev.nathan.sbaagentic.context.ContextService;
import dev.nathan.sbaagentic.context.RecallResult;
import dev.nathan.sbaagentic.recording.IngestResponse;
import dev.nathan.sbaagentic.recording.RecordingCaptureOperations;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class ContextController {

    private final ContextService contextService;
    private final RecordingCaptureOperations captureOperations;

    public ContextController(
            ContextService contextService,
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

    @GetMapping("/recall")
    public RecallResult recall(
            @RequestParam(required = false) String scope,
            @RequestParam(defaultValue = "168") int withinHours,
            @RequestParam(required = false) List<String> kinds) {
        return contextService.recall(scope, withinHours, kinds);
    }
}
