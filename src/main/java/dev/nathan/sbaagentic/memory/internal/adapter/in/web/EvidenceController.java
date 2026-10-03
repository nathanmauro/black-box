package dev.nathan.sbaagentic.memory.internal.adapter.in.web;

import dev.nathan.sbaagentic.memory.internal.application.EvidenceListQuery;
import dev.nathan.sbaagentic.memory.internal.application.EvidenceListResponse;
import dev.nathan.sbaagentic.memory.internal.application.EvidenceService;
import dev.nathan.sbaagentic.recording.CaptureEvidenceRequest;
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
@RequestMapping("/api/evidence")
public class EvidenceController {
    private final RecordingCaptureOperations capture;
    private final EvidenceService evidence;

    public EvidenceController(RecordingCaptureOperations capture, EvidenceService evidence) {
        this.capture = capture;
        this.evidence = evidence;
    }

    @PostMapping
    public IngestResponse capture(@Valid @RequestBody CaptureEvidenceRequest request) {

        return capture.captureEvidence(request);
    }

    @GetMapping
    public EvidenceListResponse list(
            @RequestParam(required = false) String target,
            @RequestParam(required = false) String project,
            @RequestParam(required = false) String repo,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Integer limit) {

        return evidence.list(new EvidenceListQuery(target, project, repo, q, limit));
    }
}
