package dev.nathan.sbaagentic.platform.internal.adapter.in.sse;

import dev.nathan.sbaagentic.memory.BroadcastChanged;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Forwards broadcast set/clear to live clients once the change is committed. */
@Component
class BroadcastStreamListener {

    private static final Logger log = LoggerFactory.getLogger(BroadcastStreamListener.class);

    private final EventBroadcaster broadcaster;

    BroadcastStreamListener(EventBroadcaster broadcaster) {
        this.broadcaster = broadcaster;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void broadcastChanged(BroadcastChanged changed) {
        try {
            broadcaster.publishBroadcastChanged(changed);
        } catch (RuntimeException ex) {
            // Best-effort: the broadcast is already committed and the list endpoint is canonical.
            log.warn("Failed to stream broadcast change {}", changed.key(), ex);
        }
    }
}
