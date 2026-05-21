package dev.nathan.sbaagentic.ai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import dev.nathan.sbaagentic.event.AgentEvent;
import dev.nathan.sbaagentic.event.EventRepository;
import dev.nathan.sbaagentic.session.AgentSession;

import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
public class SessionSummaryService {

    private final EventRepository repository;
    private final LocalAiClient localAiClient;

    public SessionSummaryService(EventRepository repository, LocalAiClient localAiClient) {
        this.repository = repository;
        this.localAiClient = localAiClient;
    }

    public AgentSession summarize(String sessionId) {
        AgentSession session = repository.findSessionById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Session not found"));
        List<AgentEvent> events = new ArrayList<>(repository.eventsForSession(sessionId, 200));
        Collections.reverse(events);

        StringBuilder transcript = new StringBuilder();
        transcript.append("Session: ").append(session.title()).append('\n');
        transcript.append("Source: ").append(session.source()).append('\n');
        if (session.cwd() != null) {
            transcript.append("CWD: ").append(session.cwd()).append('\n');
        }
        transcript.append('\n');
        for (AgentEvent event : events) {
            transcript.append('[').append(event.eventType()).append("] ");
            if (event.toolName() != null) {
                transcript.append(event.toolName()).append(": ");
            }
            if (event.text() != null) {
                transcript.append(event.text());
            }
            else if (event.toolInputJson() != null) {
                transcript.append(event.toolInputJson());
            }
            transcript.append("\n\n");
        }

        String summary = localAiClient.summarize(transcript.toString());
        repository.saveSummary(sessionId, summary);
        return repository.findSessionById(sessionId).orElse(session);
    }
}
