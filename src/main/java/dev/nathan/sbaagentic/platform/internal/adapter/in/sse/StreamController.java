package dev.nathan.sbaagentic.platform.internal.adapter.in.sse;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.time.Clock;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
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

    public StreamController(EventBroadcaster broadcaster, Clock clock) {
        this.broadcaster = broadcaster;
        this.clock = clock;
    }

    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(HttpServletRequest request) {
        // Bearer requests are stateless even if a client happens to send an unrelated cookie.
        boolean bearer = request.getHeader("Authorization") != null;
        boolean requiresSession = !bearer && request.getUserPrincipal() != null;
        HttpSession session = !bearer ? request.getSession(false) : null;
        // Logout can race between filter authentication and controller invocation. A missing
        // cookie session must not turn that authenticated request into unlimited local access.
        return broadcaster.register(() -> (!requiresSession || session != null) && sessionStillActive(session));
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
