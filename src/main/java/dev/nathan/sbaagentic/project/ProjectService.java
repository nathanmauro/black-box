package dev.nathan.sbaagentic.project;

import java.util.List;

import dev.nathan.sbaagentic.session.AgentSession;

import org.springframework.stereotype.Service;

@Service
public class ProjectService {

    private final ProjectRepository repository;

    public ProjectService(ProjectRepository repository) {
        this.repository = repository;
    }

    public List<ProjectSummary> projects() {
        return repository.summaries();
    }

    public List<AgentSession> sessions(String projectKey, int limit) {
        return repository.sessionsForProject(ProjectKeyCodec.decode(projectKey), limit);
    }

    public ProjectTimelineResponse timeline(String projectKey, int limit, int offset) {
        String canonicalKey = ProjectKeyCodec.decode(projectKey);
        return new ProjectTimelineResponse(
                ProjectKeyCodec.encode(canonicalKey),
                canonicalKey,
                ProjectKeyCodec.labelFor(canonicalKey),
                limit,
                offset,
                repository.countTimelineBlocks(canonicalKey),
                repository.timelineBlocks(canonicalKey, limit, offset));
    }

    public List<Object> melds(String projectKey) {
        ProjectKeyCodec.decode(projectKey);
        return List.of();
    }
}
