package dev.nathan.sbaagentic.summary.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.nathan.sbaagentic.recording.AgentEvent;
import dev.nathan.sbaagentic.recording.AgentSession;
import dev.nathan.sbaagentic.recording.RecordingCatalog;
import dev.nathan.sbaagentic.recording.SessionStopped;
import dev.nathan.sbaagentic.summary.SummaryBackfillResult;
import dev.nathan.sbaagentic.summary.SummaryOperations;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Background summaries at shutdown: work that finishes inside a short grace is kept, and work that
 * does not is stopped in bounded time and named so `summarize-missing` can backfill it.
 */
class SessionFinalizationServiceTest {

    private final Logger log = (Logger) LoggerFactory.getLogger(SessionFinalizationService.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final List<String> summarized = new CopyOnWriteArrayList<>();
    private Level originalLevel;

    @BeforeEach
    void captureLogs() {
        originalLevel = log.getLevel();
        log.setLevel(Level.INFO);
        logs.start();
        log.addAppender(logs);
    }

    @AfterEach
    void releaseLogs() {
        log.detachAppender(logs);
        log.setLevel(originalLevel);
    }

    @Test
    void shutdownLetsQueuedSummariesThatFinishInsideTheGraceComplete() {
        var service = new SessionFinalizationService(catalog(), summaries(sessionId -> {
            Thread.sleep(100);
            summarized.add(sessionId);
        }));
        service.summarizeAfterFinalEvent(stopped("quick-1"));
        service.summarizeAfterFinalEvent(stopped("quick-2"));

        service.shutdown();

        assertThat(summarized).containsExactly("quick-1", "quick-2");
        assertThat(warnings()).isEmpty();
    }

    @Test
    void shutdownStopsUnfinishedSummariesInBoundedTimeAndNamesThemForBackfill() throws Exception {
        var started = new CountDownLatch(1);
        var service = new SessionFinalizationService(catalog(), summaries(sessionId -> {
            started.countDown();
            new CountDownLatch(1).await(); // an external summarizer that outlives shutdown
            summarized.add(sessionId);
        }));
        service.summarizeAfterFinalEvent(stopped("running"));
        service.summarizeAfterFinalEvent(stopped("queued"));
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

        long begin = System.nanoTime();
        service.shutdown();
        Duration shutdown = Duration.ofNanos(System.nanoTime() - begin);

        assertThat(shutdown).isLessThan(SessionFinalizationService.SHUTDOWN_GRACE.plusSeconds(1));
        assertThat(summarized).isEmpty();
        assertThat(warnings()).singleElement().asString().contains("running", "queued", "summarize-missing");
    }

    @Test
    void terminalEventsAfterShutdownAreReportedForBackfillInsteadOfFailingPublication() {
        var service = new SessionFinalizationService(catalog(), summaries(summarized::add));
        service.shutdown();

        service.summarizeAfterFinalEvent(stopped("late"));

        assertThat(summarized).isEmpty();
        assertThat(warnings()).singleElement().asString().contains("late", "summarize-missing");
    }

    private List<String> warnings() {

        return logs.list.stream()
                .filter(event -> event.getLevel().isGreaterOrEqual(Level.WARN))
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    private static RecordingCatalog catalog() {
        RecordingCatalog catalog = mock(RecordingCatalog.class);
        when(catalog.findSessionById(anyString())).thenAnswer(call -> Optional.of(session(call.getArgument(0))));

        return catalog;
    }

    private static SessionStopped stopped(String sessionId) {
        var event = new AgentEvent(
                sessionId + "-stop",
                sessionId,
                "codex",
                sessionId,
                null,
                "Stop",
                "assistant",
                "done",
                null,
                null,
                null,
                Map.of(),
                Instant.parse("2026-10-15T12:00:00Z"),
                null);

        return new SessionStopped(session(sessionId), event);
    }

    private static AgentSession session(String sessionId) {

        return new AgentSession(
                sessionId,
                "codex",
                sessionId,
                "Fixture",
                "/fixture",
                null,
                Instant.parse("2026-10-15T12:00:00Z"),
                Instant.parse("2026-10-15T12:00:00Z"),
                1,
                null);
    }

    private interface Summarizer {
        void summarize(String sessionId) throws Exception;
    }

    private static SummaryOperations summaries(Summarizer summarizer) {

        return new SummaryOperations() {
            @Override
            public AgentSession summarize(String sessionId) {
                try {
                    summarizer.summarize(sessionId);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Fixture summary interrupted", ex);
                } catch (Exception ex) {
                    throw new IllegalStateException(ex);
                }

                return session(sessionId);
            }

            @Override
            public AgentSession summarize(String source, String clientSessionId) {
                throw new UnsupportedOperationException();
            }

            @Override
            public SummaryBackfillResult summarizeMissing(int limit) {
                throw new UnsupportedOperationException();
            }
        };
    }
}
