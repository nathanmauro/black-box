package dev.nathan.sbaagentic.workflow.internal.application;

import java.util.Map;
import java.util.Optional;

import dev.nathan.sbaagentic.recording.AgentEvent;
import dev.nathan.sbaagentic.recording.AgentSession;
import dev.nathan.sbaagentic.recording.EventRecorded;
import dev.nathan.sbaagentic.recording.EventTypes;
import dev.nathan.sbaagentic.recording.RecordingCatalog;
import dev.nathan.sbaagentic.workflow.CreateSessionLinkRequest;
import dev.nathan.sbaagentic.workflow.LinkDomainException;
import dev.nathan.sbaagentic.workflow.LinkErrorCode;
import dev.nathan.sbaagentic.workflow.LinkType;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Turns Claude subagent lifecycle events into {@code spawned} session links. Both
 * SubagentStart and SubagentStop carry the full parent metadata, so a link is attempted
 * on each: whichever arrives first while the parent session exists wins, the other is
 * swallowed as a duplicate. Runs synchronously inside ingest (after alias discovery at
 * order 10 and search indexing at order 20), so it must never throw for expected cases.
 */
@Component
public class SubagentLinkListener {

    private static final Logger log = LoggerFactory.getLogger(SubagentLinkListener.class);

    private final SessionLinkService links;
    private final RecordingCatalog sessions;

    public SubagentLinkListener(SessionLinkService links, RecordingCatalog sessions) {
        this.links = links;
        this.sessions = sessions;
    }

    @EventListener
    @Order(25)
    public void linkSubagentSession(EventRecorded recorded) {
        AgentEvent event = recorded.event();
        if (!isSubagentLifecycleEvent(event.eventType())) {
            return;
        }
        String parentClientSessionId = parentClientSessionId(event.metadata());
        if (parentClientSessionId == null) {
            return;
        }
        Optional<AgentSession> parent = sessions.findSession(event.source(), parentClientSessionId);
        if (parent.isEmpty()) {
            log.debug("Skipping subagent link for {}: parent {} not recorded yet",
                    event.clientSessionId(), parentClientSessionId);
            return;
        }
        try {
            links.createLink(new CreateSessionLinkRequest(
                    parent.get().id(), recorded.session().id(), LinkType.SPAWNED.value(), null));
        }
        catch (LinkDomainException ex) {
            if (ex.code() != LinkErrorCode.DUPLICATE_LINK) {
                throw ex;
            }
        }
    }

    private static boolean isSubagentLifecycleEvent(String eventType) {
        String normalized = EventTypes.normalize(eventType);
        return normalized.equals("subagentstart") || normalized.equals("subagentstop");
    }

    private static String parentClientSessionId(Map<String, Object> metadata) {
        if (metadata == null) {
            return null;
        }
        return metadata.get("parentClientSessionId") instanceof String value && !value.isBlank()
                ? value
                : null;
    }
}
