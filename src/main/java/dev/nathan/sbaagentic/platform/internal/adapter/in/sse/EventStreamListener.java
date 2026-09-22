package dev.nathan.sbaagentic.platform.internal.adapter.in.sse;

import dev.nathan.sbaagentic.recording.AgentEvent;
import dev.nathan.sbaagentic.recording.AgentSession;
import dev.nathan.sbaagentic.recording.EventRecorded;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
class EventStreamListener {

    private static final Logger log = LoggerFactory.getLogger(EventStreamListener.class);

    private final EventBroadcaster broadcaster;
    private final StreamPayloadFactory payloadFactory;

    EventStreamListener(EventBroadcaster broadcaster, StreamPayloadFactory payloadFactory) {
        this.broadcaster = broadcaster;
        this.payloadFactory = payloadFactory;
    }

    @EventListener
    @Order(30)
    public void broadcastRecordedEvent(EventRecorded recorded) {
        AgentSession session = recorded.session();
        AgentEvent event = recorded.event();
        try {
            broadcaster.publishEventAppended(payloadFactory.eventAppended(session, event));
            broadcaster.publishSessionUpdated(payloadFactory.sessionUpdated(session));
        }
        catch (RuntimeException ex) {
            // Broadcasting is best-effort; never let it break ingest.
            log.warn("Failed to broadcast recorded event {}", event == null ? null : event.id(), ex);
        }
    }
}
