package dev.nathan.sbaagentic.workflow.internal.application.port;

import java.util.List;

import dev.nathan.sbaagentic.workflow.LinkType;
import dev.nathan.sbaagentic.workflow.SessionLink;

public interface SessionLinkStore {

    SessionLink createLink(String parentSessionId, String childSessionId, LinkType linkType, String taskId);

    List<SessionLink> linksWhereParent(String sessionId);

    List<SessionLink> linksWhereChild(String sessionId);

    List<SessionLink> linksForTaskId(String taskId);
}
