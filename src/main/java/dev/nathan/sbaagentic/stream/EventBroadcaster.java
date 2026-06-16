package dev.nathan.sbaagentic.stream;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import dev.nathan.sbaagentic.event.AgentEvent;
import dev.nathan.sbaagentic.search.EventIndexSink;
import dev.nathan.sbaagentic.session.AgentSession;

import jakarta.annotation.PreDestroy;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Holds the live Server-Sent Events subscribers and fans newly persisted events out to them.
 *
 * <p>Implemented as an {@link EventIndexSink} so it plugs into the existing ingest fan-out
 * ({@code EventIngestService} already iterates every registered sink after the canonical SQLite
 * write) with no change to the ingest path. {@link #index(AgentSession, AgentEvent)} returns
 * {@code false} because broadcasting is not a durable search index — it must never flip the
 * ingest "indexed" flag, and a slow or gone subscriber must never break the canonical write.
 */
@Component
public class EventBroadcaster implements EventIndexSink {

    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    /** Registers a new subscriber. The browser's native {@code EventSource} reconnects on drop. */
    public SseEmitter register() {
        SseEmitter emitter = new SseEmitter(0L); // no server-side timeout
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(ex -> emitters.remove(emitter));
        emitters.add(emitter);
        return emitter;
    }

    @Override
    public boolean index(AgentSession session, AgentEvent event) {
        try {
            publishEventAppended(new StreamEvents.EventAppended(
                    event.sessionId(),
                    event.source(),
                    event.eventType(),
                    event.toolName(),
                    session.title(),
                    event.observedAt() == null ? null : event.observedAt().toString()));
            publishSessionUpdated(new StreamEvents.SessionUpdated(
                    session.id(),
                    session.source(),
                    session.title(),
                    session.cwd(),
                    session.eventCount(),
                    session.lastSeenAt() == null ? null : session.lastSeenAt().toString()));
        } catch (RuntimeException ex) {
            // Broadcasting is best-effort; never let it break ingest.
        }
        return false;
    }

    public void publishEventAppended(StreamEvents.EventAppended payload) {
        send("event.appended", payload);
    }

    public void publishSessionUpdated(StreamEvents.SessionUpdated payload) {
        send("session.updated", payload);
    }

    private void send(String name, Object payload) {
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name(name).data(payload, MediaType.APPLICATION_JSON));
            } catch (IOException | IllegalStateException ex) {
                emitters.remove(emitter); // subscriber gone; EventSource will reconnect
            }
        }
    }

    /** Completes any open streams on shutdown so the server never blocks waiting on idle subscribers. */
    @PreDestroy
    void closeAll() {
        for (SseEmitter emitter : emitters) {
            try {
                emitter.complete();
            } catch (RuntimeException ignored) {
                // already closing
            }
        }
        emitters.clear();
    }

    /** Visible for tests. */
    int subscriberCount() {
        return emitters.size();
    }
}
