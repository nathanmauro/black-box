package dev.nathan.sbaagentic.project.internal.application;

import dev.nathan.sbaagentic.project.internal.domain.ProjectKeyCodec;

import java.util.List;

import dev.nathan.sbaagentic.recording.AgentSession;
import dev.nathan.sbaagentic.project.ProjectAlias;
import dev.nathan.sbaagentic.project.ProjectAliasRequest;
import dev.nathan.sbaagentic.project.ProjectOperations;
import dev.nathan.sbaagentic.project.ProjectSavedMeld;
import dev.nathan.sbaagentic.project.ProjectScopeOperations;
import dev.nathan.sbaagentic.project.ProjectSummary;
import dev.nathan.sbaagentic.project.ProjectTimelineResponse;
import dev.nathan.sbaagentic.project.internal.application.port.ProjectCatalogStore;

import org.springframework.stereotype.Service;

@Service
public class ProjectService implements ProjectOperations {

    private final ProjectCatalogStore repository;
    private final ProjectAliasService aliasService;

    public ProjectService(ProjectCatalogStore repository, ProjectAliasService aliasService) {
        this.repository = repository;
        this.aliasService = aliasService;
    }

    public List<ProjectSummary> projects() {
        return repository.summaries();
    }

    public List<AgentSession> sessions(String projectKey, int limit) {
        return repository.sessionsForProject(aliasService.resolve(ProjectKeyCodec.decode(projectKey)), limit);
    }

    public ProjectTimelineResponse timeline(String projectKey, int limit, int offset) {
        String canonicalKey = aliasService.resolve(ProjectKeyCodec.decode(projectKey));
        return new ProjectTimelineResponse(
                ProjectKeyCodec.encode(canonicalKey),
                canonicalKey,
                ProjectKeyCodec.labelFor(canonicalKey),
                limit,
                offset,
                repository.countTimelineBlocks(canonicalKey),
                repository.timelineBlocks(canonicalKey, limit, offset));
    }

    public List<ProjectSavedMeld> melds(String projectKey) {
        return repository.savedMeldsForProject(aliasService.resolve(ProjectKeyCodec.decode(projectKey)));
    }

    public ProjectAlias putAlias(ProjectAliasRequest request) {
        return aliasService.put(request);
    }

    public void deleteAlias(String aliasKey) {
        aliasService.delete(aliasKey);
    }
}
