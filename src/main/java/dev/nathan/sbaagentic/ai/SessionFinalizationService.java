package dev.nathan.sbaagentic.ai;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import dev.nathan.sbaagentic.recording.AgentEvent;
import dev.nathan.sbaagentic.recording.RecordingCatalog;
import dev.nathan.sbaagentic.recording.AgentSession;

import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class SessionFinalizationService {

    private static final Logger log = LoggerFactory.getLogger(SessionFinalizationService.class);
    private static final Set<String> FINAL_EVENT_TYPES = Set.of("sessionend", "stop");

    private final RecordingCatalog repository;
    private final SessionSummaryService summaryService;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "sba-session-finalizer");
        thread.setDaemon(true);
        return thread;
    });
    private final Set<String> pending = ConcurrentHashMap.newKeySet();

    public SessionFinalizationService(RecordingCatalog repository, SessionSummaryService summaryService) {
        this.repository = repository;
        this.summaryService = summaryService;
    }

    public void summarizeAfterFinalEvent(AgentSession session, AgentEvent event) {
        if (!isFinalEvent(event.eventType()) || hasSummary(session) || !pending.add(session.id())) {
            return;
        }
        executor.execute(() -> {
            try {
                AgentSession latest = repository.findSessionById(session.id()).orElse(null);
                if (latest != null && !hasSummary(latest)) {
                    summaryService.summarize(session.id());
                }
            }
            catch (Exception ex) {
                log.warn("Black Box final summary failed for sessionId={} source={} clientSessionId={}",
                        session.id(), session.source(), session.clientSessionId(), ex);
            }
            finally {
                pending.remove(session.id());
            }
        });
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    private static boolean isFinalEvent(String eventType) {
        return eventType != null
                && FINAL_EVENT_TYPES.contains(eventType.trim().toLowerCase(Locale.ROOT));
    }

    private static boolean hasSummary(AgentSession session) {
        return session.summary() != null && !session.summary().isBlank();
    }
}
