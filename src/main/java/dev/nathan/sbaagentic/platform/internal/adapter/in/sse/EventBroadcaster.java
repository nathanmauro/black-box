package dev.nathan.sbaagentic.platform.internal.adapter.in.sse;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import org.springframework.boot.web.context.WebServerGracefulShutdownLifecycle;
import org.springframework.context.SmartLifecycle;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Live notifications wake bounded, commit-ordered database drains. Payloads remain lightweight.
 *
 * <p>Subscriptions never finish on their own, and graceful web shutdown waits for every open
 * request. The broadcaster therefore stops in the phase just before that shutdown: it ends every
 * stream and ends new ones after their opening frames. Clients reconnect with their last ID.
 */
@Component
@EnableScheduling
public class EventBroadcaster implements SmartLifecycle {
    static final int REPLAY_LIMIT = 2_000;
    static final int SHUTDOWN_PHASE = WebServerGracefulShutdownLifecycle.SMART_LIFECYCLE_PHASE + 1;

    public record Frame(String cursor, StreamEvents.EventAppended payload) {}

    public record Page(List<Frame> frames, boolean more, String resetCursor) {}

    private static final class Subscriber {
        final SseEmitter emitter = new SseEmitter(0L);
        final BooleanSupplier authorized;
        final Function<String, Page> feed;
        String cursor;
        volatile boolean closed;

        Subscriber(BooleanSupplier authorized, String cursor, Function<String, Page> feed) {
            this.authorized = authorized;
            this.cursor = cursor;
            this.feed = feed;
        }
    }

    private final List<Subscriber> subscribers = new CopyOnWriteArrayList<>();
    private volatile boolean running;
    private volatile boolean stopping;

    public SseEmitter register() {

        return register(() -> true);
    }
    // Isolated non-durable streams used by authorization/heartbeat fixtures.
    public SseEmitter register(BooleanSupplier authorized) {

        return register(authorized, null, false, null);
    }

    public SseEmitter register(BooleanSupplier authorized, String cursor, boolean reset, Function<String, Page> feed) {
        Subscriber subscriber = new Subscriber(authorized, cursor, feed);
        subscriber.emitter.onCompletion(() -> forget(subscriber));
        subscriber.emitter.onTimeout(() -> forget(subscriber));
        subscriber.emitter.onError(ex -> forget(subscriber));
        // The controller resolved its committed starting point BEFORE registration. Commits in
        // that gap are read by this first drain, and subsequent callbacks serialize on this lock.
        synchronized (subscriber) {
            subscribers.add(subscriber);
            send(subscriber, SseEmitter.event().comment("connected"));
            if (reset) {
                reset(subscriber, cursor);
            } else {
                if (cursor != null) checkpoint(subscriber);
                drain(subscriber);
            }
            // Read after the add above; stop() sets the flag before it reads the list, so either
            // stop() closes this subscriber or this request sees the flag and ends its own stream.
            if (stopping) close(subscriber);
        }

        return subscriber.emitter;
    }

    public void publishEventAppended(StreamEvents.EventAppended payload) {
        for (Subscriber subscriber : subscribers) {
            synchronized (subscriber) {
                // Callbacks can arrive out of commit order. Never advance using their payload.
                if (subscriber.feed != null) drain(subscriber);
                else
                    send(
                            subscriber,
                            SseEmitter.event().name("event.appended").data(payload, MediaType.APPLICATION_JSON));
            }
        }
    }

    public void publishSessionUpdated(StreamEvents.SessionUpdated payload) {
        publish("session.updated", payload);
    }

    public void publishJudgmentAppended(StreamEvents.JudgmentAppended payload) {
        publish("judgment.appended", payload);
    }

    @Scheduled(fixedDelay = 15_000, initialDelay = 15_000)
    void heartbeat() {
        for (Subscriber subscriber : subscribers) {
            synchronized (subscriber) {
                // Recover a committed capture even if optional publication was interrupted.
                drain(subscriber);
                send(subscriber, SseEmitter.event().comment("heartbeat"));
            }
        }
    }

    private void drain(Subscriber subscriber) {
        if (subscriber.closed || subscriber.feed == null)

            return;

        try {
            Page page = subscriber.feed.apply(subscriber.cursor);
            if (page.resetCursor() != null) {
                reset(subscriber, page.resetCursor());

                return;
            }
            for (Frame frame : page.frames()) {
                if (frame.payload() != null
                        && !send(
                                subscriber,
                                SseEmitter.event()
                                        .id(frame.cursor())
                                        .name("event.appended")
                                        .data(frame.payload(), MediaType.APPLICATION_JSON)))

                                            return;
                if (subscriber.closed)

                    return;

                subscriber.cursor = frame.cursor();
            }
            // Carries progress through deleted payloads/time-filtered positions as well.
            if (!page.frames().isEmpty()) checkpoint(subscriber);
            if (page.more()) {
                send(
                        subscriber,
                        SseEmitter.event()
                                .name("replay.more")
                                .data(Map.of("cursor", subscriber.cursor), MediaType.APPLICATION_JSON));
                close(subscriber);
            }
        } catch (RuntimeException failure) {
            // Native EventSource retries from its last successfully delivered ID.
            close(subscriber);
        }
    }

    private void checkpoint(Subscriber subscriber) {
        send(
                subscriber,
                SseEmitter.event()
                        .id(subscriber.cursor)
                        .name("stream.checkpoint")
                        .data(Map.of("cursor", subscriber.cursor), MediaType.APPLICATION_JSON));
    }

    private void reset(Subscriber subscriber, String cursor) {
        subscriber.cursor = cursor;
        send(
                subscriber,
                SseEmitter.event()
                        .id(cursor)
                        .name("replay.reset")
                        .data(Map.of("reason", "cursor-unavailable", "cursor", cursor), MediaType.APPLICATION_JSON));
        close(subscriber);
    }

    private void publish(String name, Object payload) {
        for (Subscriber subscriber : subscribers) {
            synchronized (subscriber) {
                send(subscriber, SseEmitter.event().name(name).data(payload, MediaType.APPLICATION_JSON));
            }
        }
    }

    private void forget(Subscriber subscriber) {
        subscriber.closed = true;
        subscribers.remove(subscriber);
    }

    private void close(Subscriber subscriber) {
        if (subscriber.closed)

            return;

        forget(subscriber);
        try {
            subscriber.emitter.complete();
        } catch (RuntimeException ignored) {
            /* already closing */
        }
    }

    private boolean send(Subscriber subscriber, SseEmitter.SseEventBuilder event) {
        if (subscriber.closed)

            return false;

        try {
            if (!subscriber.authorized.getAsBoolean()) {
                close(subscriber);

                return false;
            }
            subscriber.emitter.send(event);

            return true;
        } catch (IOException ex) {
            forget(subscriber); // servlet container owns completion after failed network write
        } catch (RuntimeException ex) {
            close(subscriber);
        }

        return false;
    }

    @Override
    public void start() {
        stopping = false;
        running = true;
    }

    /** Ends every stream before graceful shutdown starts waiting for open requests. */
    @Override
    public void stop() {
        stopping = true;
        running = false;
        closeAll();
    }

    @Override
    public boolean isRunning() {

        return running;
    }

    @Override
    public int getPhase() {

        return SHUTDOWN_PHASE;
    }

    @PreDestroy
    void closeAll() {
        for (Subscriber subscriber : subscribers) {
            synchronized (subscriber) {
                close(subscriber);
            }
        }
    }

    int subscriberCount() {

        return subscribers.size();
    }
}
