package dev.nathan.sbaagentic.memory.internal.adapter.in.web;

import dev.nathan.sbaagentic.memory.internal.application.EvidenceService;
import dev.nathan.sbaagentic.memory.internal.application.IdeaDetail;
import dev.nathan.sbaagentic.memory.internal.application.IdeaListQuery;
import dev.nathan.sbaagentic.memory.internal.application.IdeaListResponse;
import dev.nathan.sbaagentic.memory.internal.application.IdeaMigrationResult;
import dev.nathan.sbaagentic.memory.internal.application.IdeaService;
import dev.nathan.sbaagentic.recording.CaptureIdeaRequest;
import dev.nathan.sbaagentic.recording.IngestResponse;
import dev.nathan.sbaagentic.recording.RecordingCaptureOperations;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
public class IdeaController {

    private final RecordingCaptureOperations captureOperations;
    private final IdeaService ideas;
    private final EvidenceService evidence;

    public IdeaController(RecordingCaptureOperations captureOperations, IdeaService ideas, EvidenceService evidence) {
        this.captureOperations = captureOperations;
        this.ideas = ideas;
        this.evidence = evidence;
    }

    @PostMapping("/ideas")
    public IngestResponse captureIdea(@Valid @RequestBody CaptureIdeaRequest request) {

        return captureOperations.captureIdea(request);
    }

    @GetMapping("/ideas")
    public IdeaListResponse listIdeas(
            @RequestParam(required = false) List<String> status,
            @RequestParam(required = false) String origin,
            @RequestParam(required = false) String project,
            @RequestParam(required = false) String repo,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Integer limit) {

        return ideas.list(new IdeaListQuery(status, origin, project, repo, q, limit));
    }

    @GetMapping("/ideas/detail")
    public IdeaDetail detail(@RequestParam String ideaKey) {
        IdeaDetail detail = evidence.detailOrNull(ideaKey);
        if (detail == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Idea not found; list ideas or check the ideaKey.");
        }

        return detail;
    }

    @PostMapping("/ideas/migrate-observations")
    public IdeaMigrationResult migrateObservations(@RequestParam(defaultValue = "false") boolean apply) {

        return ideas.migrateObservations(apply);
    }
}
