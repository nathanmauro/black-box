package dev.nathan.sbaagentic.platform.internal.adapter.in.sse;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.function.BooleanSupplier;
import dev.nathan.sbaagentic.platform.internal.adapter.out.sqlite.StreamReplayRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Server-Sent Events endpoint the UI subscribes to for live updates. Push-only: the browser opens a
 * single {@code EventSource('/api/stream')} and receives {@code event.appended} / {@code
 * session.updated} frames as agents write into the recorder.
 */
@RestController
@RequestMapping("/api")
public class StreamController {

    private final EventBroadcaster broadcaster;
    private final Clock clock;
    private final StreamReplayRepository replayRepository;
    private final StreamPayloadFactory payloadFactory;

    @Autowired
    public StreamController(
            EventBroadcaster broadcaster,
            Clock clock,
            StreamReplayRepository replayRepository,
            StreamPayloadFactory payloadFactory) {
        this.broadcaster = broadcaster;
        this.clock = clock;
        this.replayRepository = replayRepository;
        this.payloadFactory = payloadFactory;
    }

    StreamController(EventBroadcaster broadcaster, Clock clock) {
        this(broadcaster, clock, null, null);
    }

    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            HttpServletRequest request,
            @RequestParam(required = false) String since,
            @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId) {
        // Bearer requests are stateless even if a client happens to send an unrelated cookie.
        boolean bearer = request.getHeader("Authorization") != null;
        boolean requiresSession = !bearer && request.getUserPrincipal() != null;
        HttpSession session = !bearer ? request.getSession(false) : null;
        // Logout can race between filter authentication and controller invocation. A missing
        // cookie session must not turn that authenticated request into unlimited local access.
        BooleanSupplier authorized = () -> (!requiresSession || session != null) && sessionStillActive(session);
        if (!hasReplayCursor(since, lastEventId)) {
            return broadcaster.register(authorized);
        }
        return broadcaster.register(authorized, () -> replay(since, lastEventId));
    }

    SseEmitter stream(HttpServletRequest request) {
        return stream(request, null, null);
    }

    private boolean hasReplayCursor(String since, String lastEventId) {
        return (lastEventId != null && !lastEventId.isBlank()) || (since != null && !since.isBlank());
    }

    private List<StreamEvents.EventAppended> replay(String since, String lastEventId) {
        if (lastEventId != null && !lastEventId.isBlank()) {
            if (replayRepository == null || payloadFactory == null) {
                return List.of();
            }
            return replayRepository.eventsAfterCursor(lastEventId).stream()
                    .map(payloadFactory::eventAppended)
                    .toList();
        }
        if (since == null || since.isBlank()) {
            return List.of();
        }
        if (replayRepository == null || payloadFactory == null) {
            return List.of();
        }
        try {
            return replayRepository.eventsSince(Instant.parse(since)).stream()
                    .map(payloadFactory::eventAppended)
                    .toList();
        }
        catch (DateTimeParseException ex) {
            return List.of();
        }
    }

    private boolean sessionStillActive(HttpSession session) {
        if (session == null) return true; // local access or authenticated stateless Bearer
        try {
            int idleSeconds = session.getMaxInactiveInterval();
            return idleSeconds <= 0 || clock.millis() - session.getLastAccessedTime() < idleSeconds * 1000L;
        } catch (IllegalStateException invalidated) {
            return false; // logout or container expiry
        }
    }
}
