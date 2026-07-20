package dev.nathan.sbaagentic.summary.internal.application;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import dev.nathan.sbaagentic.recording.AgentEvent;
import dev.nathan.sbaagentic.recording.RecordingCatalog;
import dev.nathan.sbaagentic.recording.AgentSession;
import dev.nathan.sbaagentic.recording.TitleRank;
import dev.nathan.sbaagentic.summary.SummaryBackfillResult;
import dev.nathan.sbaagentic.summary.SummaryOperations;

import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;

@Service
public class SessionSummaryService implements SummaryOperations {

    private static final int MAX_BACKFILL_LIMIT = 25;

    private final RecordingCatalog repository;
    private final SummaryBackend summaryBackend;

    public SessionSummaryService(RecordingCatalog repository, SummaryBackend summaryBackend) {
        this.repository = repository;
        this.summaryBackend = summaryBackend;
    }

    public AgentSession summarize(String sessionId) {
        AgentSession session = repository.findSessionById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Session not found"));
        List<AgentEvent> events = new ArrayList<>(repository.eventsForSession(sessionId, summaryEventLimit(session)));
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

        // Call the configured summary backend outside any transaction, then commit the summary and
        // derived title — which outranks every ingest-time title — in one atomic write.
        String summary;
        try {
            summary = summaryBackend.summarize(transcript.toString());
        }
        catch (IllegalStateException ex) {
            throw new ResponseStatusException(SERVICE_UNAVAILABLE,
                    "Summary backend failed or produced no summary", ex);
        }
        repository.saveSummaryAndTitle(sessionId, summary, summaryBackend.title(summary), TitleRank.AI);
        return repository.findSessionById(sessionId).orElse(session);
    }

    public AgentSession summarize(String source, String clientSessionId) {
        AgentSession session = repository.findSession(source, clientSessionId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Session not found"));
        return summarize(session.id());
    }

    public SummaryBackfillResult summarizeMissing(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, MAX_BACKFILL_LIMIT));
        List<AgentSession> missing = repository.recentSessionsMissingSummary(safeLimit);
        List<AgentSession> summarized = new ArrayList<>();
        for (AgentSession session : missing) {
            summarized.add(summarize(session.id()));
        }
        return new SummaryBackfillResult(safeLimit, summarized.size(), summarized);
    }

    private static int summaryEventLimit(AgentSession session) {
        long count = session.eventCount();
        if (count <= 0) {
            return 200;
        }
        return (int) Math.max(200, Math.min(count, 2_000));
    }
}
