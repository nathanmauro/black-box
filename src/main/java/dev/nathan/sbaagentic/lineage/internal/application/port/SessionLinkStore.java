package dev.nathan.sbaagentic.lineage.internal.application.port;

import dev.nathan.sbaagentic.lineage.LinkType;
import dev.nathan.sbaagentic.lineage.SessionLink;
import java.util.List;
import java.util.Map;

public interface SessionLinkStore {

    SessionLink createLink(String parentSessionId, String childSessionId, LinkType linkType);

    List<SessionLink> linksWhereParent(String sessionId);

    List<SessionLink> linksWhereChild(String sessionId);

    Map<String, Long> childCounts(List<String> parentSessionIds);
}
