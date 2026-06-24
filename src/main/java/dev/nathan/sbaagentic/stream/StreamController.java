package dev.nathan.sbaagentic.stream;

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

    public StreamController(EventBroadcaster broadcaster) {
        this.broadcaster = broadcaster;
    }

    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        return broadcaster.register();
    }
}
