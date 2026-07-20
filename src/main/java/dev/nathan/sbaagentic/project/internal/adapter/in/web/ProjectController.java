package dev.nathan.sbaagentic.project.internal.adapter.in.web;

import java.util.List;

import dev.nathan.sbaagentic.project.ProjectAlias;
import dev.nathan.sbaagentic.project.ProjectAliasRequest;
import dev.nathan.sbaagentic.project.ProjectMeldPreviewRequest;
import dev.nathan.sbaagentic.project.ProjectMeldPreviewResponse;
import dev.nathan.sbaagentic.project.ProjectMeldSaveRequest;
import dev.nathan.sbaagentic.project.ProjectMeldService;
import dev.nathan.sbaagentic.project.ProjectSavedMeld;
import dev.nathan.sbaagentic.project.ProjectService;
import dev.nathan.sbaagentic.project.ProjectSummary;
import dev.nathan.sbaagentic.project.ProjectTimelineResponse;
import dev.nathan.sbaagentic.recording.AgentSession;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
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

    private final ProjectService projectService;
    private final ProjectMeldService projectMeldService;

    public ProjectController(ProjectService projectService, ProjectMeldService projectMeldService) {
        this.projectService = projectService;
        this.projectMeldService = projectMeldService;
    }

    @GetMapping("/projects")
    public List<ProjectSummary> projects() {
        return projectService.projects();
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
            @PathVariable String projectKey,
            @RequestParam(defaultValue = "100") int limit) {
        return projectService.sessions(projectKey, safeLimit(limit));
    }

    @GetMapping("/projects/{projectKey}/timeline")
    public ProjectTimelineResponse projectTimeline(
            @PathVariable String projectKey,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        return projectService.timeline(projectKey, safeLimit(limit), safeOffset(offset));
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
            @PathVariable String projectKey,
            @RequestBody ProjectMeldPreviewRequest request) {
        return projectMeldService.preview(projectKey, request);
    }

    private static int safeLimit(int limit) {
        return Math.max(1, Math.min(limit, 250));
    }

    private static int safeOffset(int offset) {
        return Math.max(0, offset);
    }
}
