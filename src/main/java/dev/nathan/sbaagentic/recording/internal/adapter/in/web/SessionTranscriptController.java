package dev.nathan.sbaagentic.recording.internal.adapter.in.web;

import dev.nathan.sbaagentic.recording.SessionTranscriptOperations;
import dev.nathan.sbaagentic.recording.SessionTranscriptResponse;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sessions")
public class SessionTranscriptController {

    private final SessionTranscriptOperations transcripts;

    public SessionTranscriptController(SessionTranscriptOperations transcripts) {
        this.transcripts = transcripts;
    }

    @GetMapping("/{sessionId}/transcript")
    public SessionTranscriptResponse transcript(
            @PathVariable String sessionId,
            @RequestParam(required = false, name = "q") String query,
            @RequestParam(required = false) String before,
            @RequestParam(defaultValue = "100") int limit) {
        return transcripts.transcript(sessionId, query, before, Math.max(1, Math.min(limit, 250)));
    }
}
