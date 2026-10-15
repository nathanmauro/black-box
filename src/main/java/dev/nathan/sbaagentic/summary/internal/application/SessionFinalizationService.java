package dev.nathan.sbaagentic.summary.internal.application;

import dev.nathan.sbaagentic.recording.AgentEvent;
import dev.nathan.sbaagentic.recording.AgentSession;
import dev.nathan.sbaagentic.recording.RecordingCatalog;
import dev.nathan.sbaagentic.recording.SessionStopped;
import dev.nathan.sbaagentic.summary.SummaryOperations;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * Summarizes a session in the background after its final event. At shutdown, queued and running
 * summaries get {@link #SHUTDOWN_GRACE} to finish; the rest are stopped and named in the log. Their
 * sessions keep no summary, so {@code summarize-missing} (CLI or HTTP) can backfill them.
 */
@Service
public class SessionFinalizationService {

    /** Short enough to keep shutdown inside a service manager's exit timeout. */
    static final Duration SHUTDOWN_GRACE = Duration.ofSeconds(1);

    /** Lets an interrupted summary stop its external command before the JVM exits. */
    private static final Duration INTERRUPTED_STOP = Duration.ofMillis(500);

    private static final Logger log = LoggerFactory.getLogger(SessionFinalizationService.class);
    private static final String BACKFILL =
            "run summarize-missing (CLI) or POST /api/sessions/summarize-missing to backfill";
    private final RecordingCatalog repository;
    private final SummaryOperations summaryService;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "sba-session-finalizer");
        thread.setDaemon(true);

        return thread;
    });
    private final Set<String> pending = ConcurrentHashMap.newKeySet();
    private volatile boolean abandoning;

    public SessionFinalizationService(RecordingCatalog repository, SummaryOperations summaryService) {
        this.repository = repository;
        this.summaryService = summaryService;
    }

    @EventListener
    public void summarizeAfterFinalEvent(SessionStopped stopped) {
        AgentSession session = stopped.session();
        AgentEvent event = stopped.event();
        if (hasSummary(session) || !pending.add(session.id())) {

            return;
        }
        try {
            executor.execute(() -> summarize(session));
        } catch (RejectedExecutionException shuttingDown) {
            pending.remove(session.id());
            log.warn("Shutdown began before the summary of sessionId={} was scheduled; {}", session.id(), BACKFILL);
        }
    }

    private void summarize(AgentSession session) {
        try {
            AgentSession latest = repository.findSessionById(session.id()).orElse(null);
            if (latest != null && !hasSummary(latest)) {
                summaryService.summarize(session.id());
            }
        } catch (Exception ex) {
            if (abandoning) {
                // Shutdown interrupted it, and its warning already names this session.
                log.debug("Final summary for sessionId={} stopped by shutdown", session.id(), ex);
            } else {
                log.warn(
                        "Black Box final summary failed for sessionId={} source={} clientSessionId={}",
                        session.id(),
                        session.source(),
                        session.clientSessionId(),
                        ex);
            }
        } finally {
            pending.remove(session.id());
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
        if (awaitTermination(SHUTDOWN_GRACE))

            return;

        List<String> unfinished = List.copyOf(pending);
        abandoning = true;
        executor.shutdownNow();
        awaitTermination(INTERRUPTED_STOP);
        if (unfinished.isEmpty())

            return;

        log.warn(
                "Shutdown stopped {} unfinished background session summaries, sessionIds={}; {}",
                unfinished.size(),
                unfinished,
                BACKFILL);
    }

    private boolean awaitTermination(Duration timeout) {
        try {

            return executor.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();

            return false;
        }
    }

    private static boolean hasSummary(AgentSession session) {

        return session.summary() != null && !session.summary().isBlank();
    }
}
