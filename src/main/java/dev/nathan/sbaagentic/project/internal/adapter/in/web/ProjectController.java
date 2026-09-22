package dev.nathan.sbaagentic.project.internal.adapter.in.web;

import dev.nathan.sbaagentic.project.CodeNavigationOperations;
import dev.nathan.sbaagentic.project.CodeNavigationResult;
import dev.nathan.sbaagentic.project.CodeProjectScope;
import dev.nathan.sbaagentic.project.CodeReference;
import dev.nathan.sbaagentic.project.ProjectAlias;
import dev.nathan.sbaagentic.project.ProjectAliasRequest;
import dev.nathan.sbaagentic.project.ProjectGraphOperations;
import dev.nathan.sbaagentic.project.ProjectMeldOperations;
import dev.nathan.sbaagentic.project.ProjectMeldPreviewRequest;
import dev.nathan.sbaagentic.project.ProjectMeldPreviewResponse;
import dev.nathan.sbaagentic.project.ProjectMeldSaveRequest;
import dev.nathan.sbaagentic.project.ProjectOperations;
import dev.nathan.sbaagentic.project.ProjectSavedMeld;
import dev.nathan.sbaagentic.project.ProjectSummary;
import dev.nathan.sbaagentic.project.ProjectTimelineResponse;
import dev.nathan.sbaagentic.project.ProjectTrajectoryResponse;
import dev.nathan.sbaagentic.project.internal.application.CodeNavigationException;
import dev.nathan.sbaagentic.recording.AgentSession;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class ProjectController {

    private final ProjectOperations projectService;
    private final ProjectMeldOperations projectMeldService;
    private final ProjectGraphOperations projectGraphService;
    private final CodeNavigationOperations codeNavigationService;

    public ProjectController(
            ProjectOperations projectService,
            ProjectMeldOperations projectMeldService,
            ProjectGraphOperations projectGraphService,
            CodeNavigationOperations codeNavigationService) {
        this.projectService = projectService;
        this.projectMeldService = projectMeldService;
        this.projectGraphService = projectGraphService;
        this.codeNavigationService = codeNavigationService;
    }

    @GetMapping("/projects")
    public List<ProjectSummary> projects() {

        return projectService.projects();
    }

    @GetMapping("/projects/code-scopes")
    public List<CodeProjectScope> codeScopes() {

        return codeNavigationService.codeScopes();
    }

    @PostMapping("/open-in-editor")
    public CodeNavigationResult openInEditor(@RequestBody CodeReference reference) {

        return codeNavigationService.openInEditor(reference);
    }

    @PostMapping("/reveal-in-finder")
    public CodeNavigationResult revealInFinder(@RequestBody CodeReference reference) {

        return codeNavigationService.revealInFinder(reference);
    }

    @PutMapping("/project-aliases")
    public ProjectAlias putProjectAlias(@RequestBody ProjectAliasRequest request) {

        return projectService.putAlias(request);
    }

    @DeleteMapping("/project-aliases")
    public ResponseEntity<Void> deleteProjectAlias(@RequestParam String aliasKey) {
        projectService.deleteAlias(aliasKey);

        return ResponseEntity.noContent().build();
    }

    @GetMapping("/projects/{projectKey}/sessions")
    public List<AgentSession> projectSessions(
            @PathVariable String projectKey, @RequestParam(defaultValue = "100") int limit) {

        return projectService.sessions(projectKey, safeLimit(limit));
    }

    @GetMapping("/projects/{projectKey}/timeline")
    public ProjectTimelineResponse projectTimeline(
            @PathVariable String projectKey,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset) {

        return projectService.timeline(projectKey, safeLimit(limit), safeOffset(offset));
    }

    @GetMapping("/projects/{projectKey}/graph")
    public ProjectTrajectoryResponse projectGraph(@PathVariable String projectKey) {

        return projectGraphService.graph(projectKey);
    }

    @GetMapping("/projects/{projectKey}/melds")
    public List<ProjectSavedMeld> projectMelds(@PathVariable String projectKey) {

        return projectService.melds(projectKey);
    }

    @PostMapping("/melds")
    public ProjectSavedMeld saveProjectMeld(@RequestBody ProjectMeldSaveRequest request) {

        return projectMeldService.save(request);
    }

    @PostMapping("/projects/{projectKey}/melds/preview")
    public ProjectMeldPreviewResponse previewProjectMeld(
            @PathVariable String projectKey, @RequestBody ProjectMeldPreviewRequest request) {

        return projectMeldService.preview(projectKey, request);
    }

    @ExceptionHandler(CodeNavigationException.class)
    public ResponseEntity<NavigationErrorResponse> handleCodeNavigation(CodeNavigationException ex) {
        HttpStatus status =
                switch (ex.code()) {
                    case INVALID_REFERENCE -> HttpStatus.BAD_REQUEST;
                    case OUTSIDE_PROJECT_ROOT -> HttpStatus.FORBIDDEN;
                    case FILE_MISSING -> HttpStatus.NOT_FOUND;
                    case PROJECT_UNRESOLVED -> HttpStatus.CONFLICT;
                    case EDITOR_DISABLED, REVEAL_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
                };

        return ResponseEntity.status(status)
                .body(new NavigationErrorResponse(
                        new NavigationErrorBody(status.value(), ex.code().type(), ex.getMessage())));
    }

    private static int safeLimit(int limit) {

        return Math.max(1, Math.min(limit, 250));
    }

    private static int safeOffset(int offset) {

        return Math.max(0, offset);
    }

    private record NavigationErrorResponse(NavigationErrorBody error) {}

    private record NavigationErrorBody(int status, String type, String message) {}
}
