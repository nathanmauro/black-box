package dev.nathan.sbaagentic.platform.internal.adapter.in.sse;

import dev.nathan.sbaagentic.platform.internal.adapter.out.sqlite.StreamReplayRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.function.BooleanSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
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
        if (replayRepository == null)

            return broadcaster.register(authorized);

        Instant filter = null;
        if (since != null && !since.isBlank()) {
            try {
                filter = Instant.parse(since);
            } catch (DateTimeParseException ex) {
                throw new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.BAD_REQUEST, "Invalid since timestamp");
            }
        }
        Instant sinceFilter = filter;
        var start = replayRepository.start(lastEventId, sinceFilter);

        return broadcaster.register(authorized, start.cursor(), start.reset(), cursor -> {
            var page = replayRepository.page(cursor, sinceFilter, EventBroadcaster.REPLAY_LIMIT);

            return new EventBroadcaster.Page(
                    page.entries().stream()
                            .map(entry -> new EventBroadcaster.Frame(
                                    entry.cursor(),
                                    entry.event() == null ? null : payloadFactory.eventAppended(entry.event())))
                            .toList(),
                    page.more(),
                    page.resetCursor());
        });
    }

    SseEmitter stream(HttpServletRequest request) {

        return stream(request, null, null);
    }

    private boolean sessionStillActive(HttpSession session) {
        if (session == null)

            return true; // local access or authenticated stateless Bearer

        try {
            int idleSeconds = session.getMaxInactiveInterval();

            return idleSeconds <= 0 || clock.millis() - session.getLastAccessedTime() < idleSeconds * 1000L;
        } catch (IllegalStateException invalidated) {

            return false; // logout or container expiry
        }
    }
}
