package dev.nathan.sbaagentic.memory.internal.adapter.in.web;

import dev.nathan.sbaagentic.memory.internal.application.IdeaListQuery;
import dev.nathan.sbaagentic.memory.internal.application.IdeaListResponse;
import dev.nathan.sbaagentic.memory.internal.application.IdeaMigrationResult;
import dev.nathan.sbaagentic.memory.internal.application.IdeaService;
import dev.nathan.sbaagentic.recording.CaptureIdeaRequest;
import dev.nathan.sbaagentic.recording.IngestResponse;
import dev.nathan.sbaagentic.recording.RecordingCaptureOperations;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class IdeaController {

    private final RecordingCaptureOperations captureOperations;
    private final IdeaService ideas;

    public IdeaController(RecordingCaptureOperations captureOperations, IdeaService ideas) {
        this.captureOperations = captureOperations;
        this.ideas = ideas;
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

    @PostMapping("/ideas/migrate-observations")
    public IdeaMigrationResult migrateObservations(@RequestParam(defaultValue = "false") boolean apply) {

        return ideas.migrateObservations(apply);
    }
}
