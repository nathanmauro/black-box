package dev.nathan.sbaagentic.workflow;

import java.util.List;
import java.util.Map;

/** Session-lineage use cases and projections owned by workflow. */
public interface SessionLineageOperations {

    SessionLink createLink(CreateSessionLinkRequest request);

    SessionLinksResponse linksForSession(String sessionId);

    List<SessionLink> linksWhereParent(String sessionId);

    List<SessionLink> linksWhereChild(String sessionId);

    List<SessionLink> linksForTask(String taskId);

    Map<String, Long> childCounts(List<String> sessionIds);
}
