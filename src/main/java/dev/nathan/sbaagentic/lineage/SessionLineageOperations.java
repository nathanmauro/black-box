package dev.nathan.sbaagentic.lineage;

import java.util.List;
import java.util.Map;

/** Session-lineage use cases and projections owned by lineage. */
public interface SessionLineageOperations {

    SessionLink createLink(CreateSessionLinkRequest request);

    SessionLinksResponse linksForSession(String sessionId);

    List<SessionLink> linksWhereParent(String sessionId);

    List<SessionLink> linksWhereChild(String sessionId);

    Map<String, Long> childCounts(List<String> sessionIds);
}
