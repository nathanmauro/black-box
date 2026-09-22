package dev.nathan.sbaagentic.project;

import dev.nathan.sbaagentic.recording.AgentSession;
import java.util.List;

public interface ProjectOperations {

    List<ProjectSummary> projects();

    List<AgentSession> sessions(String projectKey, int limit);

    ProjectTimelineResponse timeline(String projectKey, int limit, int offset);

    List<ProjectSavedMeld> melds(String projectKey);

    ProjectAlias putAlias(ProjectAliasRequest request);

    void deleteAlias(String aliasKey);
}
