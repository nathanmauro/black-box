package dev.nathan.sbaagentic.project.internal.application.port;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import dev.nathan.sbaagentic.project.ProjectSavedMeld;
import dev.nathan.sbaagentic.project.ProjectSummary;
import dev.nathan.sbaagentic.project.ProjectTimelineBlock;
import dev.nathan.sbaagentic.recording.AgentSession;

public interface ProjectCatalogStore {

    List<ProjectSummary> summaries();

    List<AgentSession> sessionsForProject(String canonicalKey, int limit);

    List<AgentSession> sessionsForProjectByIds(String canonicalKey, List<String> sessionIds);

    long countTimelineBlocks(String canonicalKey);

    List<ProjectTimelineBlock> timelineBlocks(String canonicalKey, int limit, int offset);

    List<ProjectTimelineBlock> timelineBlocksForSession(
            String canonicalKey, String sessionId, int limit);

    void insertSavedMeld(
            String id,
            String canonicalKey,
            String title,
            String body,
            String provider,
            String model,
            String promptVersion,
            String executionMode,
            boolean savedFromPreview,
            Map<String, Object> metadata,
            Instant createdAt,
            List<AgentSession> sessions);

    List<ProjectSavedMeld> savedMeldsForProject(String canonicalKey);
}
