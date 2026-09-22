package dev.nathan.sbaagentic.platform.internal.adapter.in.sse;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Holds the live Server-Sent Events subscribers and fans newly persisted events out to them.
 *
 * <p>Reacts only after the canonical SQLite write. A slow or gone subscriber is isolated here and
 * can never break ingestion.
 */
@Component
@EnableScheduling
public class EventBroadcaster {

    static final int REPLAY_LIMIT = 2_000;

    private record Pending(SseEmitter.SseEventBuilder frame, StreamEvents.EventAppended event) {}

    private static final class Subscriber {
        final SseEmitter emitter;
        final BooleanSupplier authorized;
        final List<Pending> pending = new ArrayList<>();
        boolean replaying = true;
        boolean closed;

        Subscriber(SseEmitter emitter, BooleanSupplier authorized) {
            this.emitter = emitter;
            this.authorized = authorized;
        }

        SseEmitter emitter() {

            return emitter;
        }

        BooleanSupplier authorized() {

            return authorized;
        }
    }

    private final List<Subscriber> subscribers = new CopyOnWriteArrayList<>();

    public EventBroadcaster() {}

    /** Registers a new subscriber. The browser's native {@code EventSource} reconnects on drop. */
    public SseEmitter register() {

        return register(() -> true);
    }

    /** Recheck a browser session before each publication; an open stream must not outlive access. */
    public SseEmitter register(BooleanSupplier authorized) {

        return register(authorized, List::of);
    }

    /** Registers a subscriber and replays missed event.appended frames before live delivery. */
    public SseEmitter register(BooleanSupplier authorized, List<StreamEvents.EventAppended> replay) {

        return register(authorized, () -> replay);
    }

    /**
     * Registers the subscriber before loading replay frames, then sends replay frames to that same
     * subscriber. This keeps expensive replay work out of the pre-subscription path.
     */
    public SseEmitter register(BooleanSupplier authorized, Supplier<List<StreamEvents.EventAppended>> replay) {
        SseEmitter emitter = new SseEmitter(0L); // no server-side timeout
        Subscriber subscriber = new Subscriber(emitter, authorized);
        emitter.onCompletion(() -> subscribers.remove(subscriber));
        emitter.onTimeout(() -> subscribers.remove(subscriber));
        emitter.onError(ex -> subscribers.remove(subscriber));
        subscribers.add(subscriber);
        // Flush the response immediately so the browser fires `open` (and the UI shows "live")
        // right away, instead of staying "connecting" until the first real event is published.
        send(subscriber, SseEmitter.event().comment("connected"));
        try {
            List<StreamEvents.EventAppended> history = replay.get();
            synchronized (subscriber) {
                if (subscriber.closed)

                    return emitter;

                Set<String> sent = new HashSet<>();
                for (StreamEvents.EventAppended payload :
                        history.stream().limit(REPLAY_LIMIT).toList()) {
                    sendEventAppended(subscriber, payload);
                    sent.add(payload.id());
                }
                if (history.size() > REPLAY_LIMIT) {
                    // Never jump into live traffic past an omitted page. Native EventSource
                    // resumes at the final delivered ID; other clients get an explicit signal.
                    send(
                            subscriber,
                            SseEmitter.event()
                                    .name("replay.more")
                                    .data(
                                            java.util.Map.of("cursor", cursor(history.get(REPLAY_LIMIT - 1))),
                                            MediaType.APPLICATION_JSON));
                    close(subscriber);

                    return emitter;
                }
                for (Pending pending : subscriber.pending) {
                    if (pending.event() == null) send(subscriber, pending.frame());
                    else if (sent.add(pending.event().id())) sendEventAppended(subscriber, pending.event());
                }
                subscriber.pending.clear();
                subscriber.replaying = false;
            }
        } catch (RuntimeException failure) {
            close(subscriber);
            throw failure;
        }

        return emitter;
    }

    public void publishEventAppended(StreamEvents.EventAppended payload) {
        for (Subscriber subscriber : subscribers) {
            dispatch(subscriber, new Pending(null, payload));
        }
    }

    public void publishSessionUpdated(StreamEvents.SessionUpdated payload) {
        send("session.updated", payload);
    }

    public void publishTaskChanged(StreamEvents.TaskChanged payload) {
        send(payload.transitionType(), payload);
    }

    public void publishTaskNote(StreamEvents.TaskNoted payload) {
        send("task.note", payload);
    }

    public void publishJudgmentAppended(StreamEvents.JudgmentAppended payload) {
        send("judgment.appended", payload);
    }

    /** One shared Spring scheduler keeps idle proxy connections active; comments create no events. */
    @Scheduled(fixedDelay = 15_000, initialDelay = 15_000)
    void heartbeat() {
        for (Subscriber subscriber : subscribers) {
            dispatch(subscriber, new Pending(SseEmitter.event().comment("heartbeat"), null));
        }
    }

    private void send(String name, Object payload) {
        for (Subscriber subscriber : subscribers) {
            dispatch(
                    subscriber,
                    new Pending(SseEmitter.event().name(name).data(payload, MediaType.APPLICATION_JSON), null));
        }
    }

    private void dispatch(Subscriber subscriber, Pending pending) {
        synchronized (subscriber) {
            if (subscriber.closed)

                return;

            if (subscriber.replaying) {
                if (subscriber.pending.size() >= REPLAY_LIMIT) {
                    send(
                            subscriber,
                            SseEmitter.event()
                                    .name("replay.reset")
                                    .data(
                                            java.util.Map.of("reason", "live-buffer-overflow"),
                                            MediaType.APPLICATION_JSON));
                    close(subscriber);
                } else subscriber.pending.add(pending);
            } else if (pending.event() == null) send(subscriber, pending.frame());
            else sendEventAppended(subscriber, pending.event());
        }
    }

    private void close(Subscriber subscriber) {
        subscriber.closed = true;
        subscriber.pending.clear();
        subscribers.remove(subscriber);
        subscriber.emitter().complete();
    }

    private void sendEventAppended(Subscriber subscriber, StreamEvents.EventAppended payload) {
        SseEmitter.SseEventBuilder event =
                SseEmitter.event().id(cursor(payload)).name("event.appended").data(payload, MediaType.APPLICATION_JSON);
        send(subscriber, event);
    }

    private static String cursor(StreamEvents.EventAppended payload) {

        return (payload.observedAt() == null ? "" : payload.observedAt()) + "|" + payload.id();
    }

    private void send(Subscriber subscriber, SseEmitter.SseEventBuilder event) {
        try {
            // Heartbeats also expire idle/logged-out browser streams even when no agent writes.
            if (!subscriber.authorized().getAsBoolean()) {
                subscribers.remove(subscriber);
                subscriber.emitter().complete();

                return;
            }
            subscriber.emitter().send(event);
        } catch (IOException ex) {
            // The servlet container owns completion after a failed network write.
            subscribers.remove(subscriber);
        } catch (RuntimeException ex) {
            // One invalid session/emitter must not stop the shared heartbeat or other subscribers.
            subscribers.remove(subscriber);
            try {
                subscriber.emitter().complete();
            } catch (RuntimeException ignored) {
                // already closing
            }
        }
    }

    /** Completes any open streams on shutdown so the server never blocks waiting on idle subscribers. */
    @PreDestroy
    void closeAll() {
        for (Subscriber subscriber : subscribers) {
            try {
                subscriber.emitter().complete();
            } catch (RuntimeException ignored) {
                // already closing
            }
        }
        subscribers.clear();
    }

    /** Visible for tests. */
    int subscriberCount() {

        return subscribers.size();
    }
}
