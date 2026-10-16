package dev.nathan.sbaagentic.summary.internal.application;

import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;

import dev.nathan.sbaagentic.recording.AgentEvent;
import dev.nathan.sbaagentic.recording.AgentSession;
import dev.nathan.sbaagentic.recording.ExportRedactor;
import dev.nathan.sbaagentic.recording.IngestionRedactor;
import dev.nathan.sbaagentic.recording.RecordingCatalog;
import dev.nathan.sbaagentic.recording.SessionSummaryRecorded;
import dev.nathan.sbaagentic.recording.TitleRank;
import dev.nathan.sbaagentic.summary.SummaryBackfillResult;
import dev.nathan.sbaagentic.summary.SummaryOperations;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SessionSummaryService implements SummaryOperations {

    private static final int MAX_BACKFILL_LIMIT = 25;

    private final RecordingCatalog repository;
    private final SummaryBackend summaryBackend;
    private final ApplicationEventPublisher eventPublisher;
    private final ExportRedactor exportRedactor;
    private final IngestionRedactor ingestionRedactor;

    public SessionSummaryService(
            RecordingCatalog repository,
            SummaryBackend summaryBackend,
            ApplicationEventPublisher eventPublisher,
            ExportRedactor exportRedactor,
            IngestionRedactor ingestionRedactor) {
        this.repository = repository;
        this.summaryBackend = summaryBackend;
        this.eventPublisher = eventPublisher;
        this.exportRedactor = exportRedactor;
        this.ingestionRedactor = ingestionRedactor;
    }

    public AgentSession summarize(String sessionId) {
        AgentSession session = repository
                .findSessionById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Session not found"));
        List<AgentEvent> events = new ArrayList<>(repository.eventsForSession(sessionId, summaryEventLimit(session)));
        Collections.reverse(events);

        // The backend may be an external command or a remote model, and stored text can predate a
        // redaction rule. The built-in credential rules run on each piece, so a long transcript is not
        // clipped to one scan budget, and text without a recognized credential is sent as stored.
        StringBuilder transcript = new StringBuilder();
        transcript.append("Session: ").append(outbound(session.title())).append('\n');
        transcript.append("Source: ").append(outbound(session.source())).append('\n');
        if (session.cwd() != null) {
            transcript.append("CWD: ").append(outbound(session.cwd())).append('\n');
        }
        transcript.append('\n');
        for (AgentEvent event : events) {
            transcript.append('[').append(outbound(event.eventType())).append("] ");
            if (event.toolName() != null) {
                transcript.append(outbound(event.toolName())).append(": ");
            }
            if (event.text() != null) {
                transcript.append(outbound(event.text()));
            } else if (event.toolInputJson() != null) {
                transcript.append(outbound(event.toolInputJson()));
            }
            transcript.append("\n\n");
        }

        // Call the configured summary backend outside any transaction, then commit the summary and
        // derived title — which outranks every ingest-time title — in one atomic write. A model can
        // echo a credential, so its answer is redacted like any other accepted text before storage.
        String summary;
        try {
            summary = ingestionRedactor.redact(summaryBackend.summarize(transcript.toString()));
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(SERVICE_UNAVAILABLE, "Summary backend failed or produced no summary", ex);
        }
        String title = ingestionRedactor.redact(summaryBackend.title(outbound(summary)));
        repository.saveSummaryAndTitle(sessionId, summary, title, TitleRank.AI);
        AgentSession updated = repository.findSessionById(sessionId).orElse(session);
        eventPublisher.publishEvent(new SessionSummaryRecorded(updated));

        return updated;
    }

    public AgentSession summarize(String source, String clientSessionId) {
        AgentSession session = repository
                .findSession(source, clientSessionId)
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

    private String outbound(String text) {

        return exportRedactor.redactForSummary(text);
    }

    private static int summaryEventLimit(AgentSession session) {
        long count = session.eventCount();
        if (count <= 0) {

            return 200;
        }

        return (int) Math.max(200, Math.min(count, 2_000));
    }
}
