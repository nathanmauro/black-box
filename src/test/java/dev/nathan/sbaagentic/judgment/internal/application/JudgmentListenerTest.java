package dev.nathan.sbaagentic.judgment.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nathan.sbaagentic.judgment.JudgmentPublication;
import dev.nathan.sbaagentic.judgment.internal.application.port.Judge;
import dev.nathan.sbaagentic.judgment.internal.application.port.JudgeStats;
import dev.nathan.sbaagentic.judgment.internal.application.port.JudgmentRepository;
import dev.nathan.sbaagentic.judgment.internal.domain.BeatState;
import dev.nathan.sbaagentic.memory.internal.application.EmbeddingIndexer;
import dev.nathan.sbaagentic.recording.AgentEvent;
import dev.nathan.sbaagentic.recording.AgentSession;
import dev.nathan.sbaagentic.recording.EventRecorded;
import java.time.Clock;
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
import org.springframework.core.annotation.Order;

class JudgmentListenerTest {

    private final Logger log = (Logger) LoggerFactory.getLogger(JudgmentListener.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final List<String> judged = new CopyOnWriteArrayList<>();
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
    void listenerRunsAfterEmbeddingIndexer() throws Exception {
        int judgmentOrder = JudgmentListener.class
                .getMethod("judgeRecordedEvent", EventRecorded.class)
                .getAnnotation(Order.class)
                .value();
        int embeddingOrder = EmbeddingIndexer.class
                .getMethod("indexRecordedEvent", EventRecorded.class)
                .getAnnotation(Order.class)
                .value();

        assertThat(embeddingOrder).isEqualTo(30);
        assertThat(judgmentOrder).isEqualTo(35);
    }

    @Test
    void shutdownLetsJudgmentsThatFinishInsideTheGraceComplete() {
        var listener = listener(sessionId -> {
            Thread.sleep(100);
            judged.add(sessionId);
        });
        listener.judgeRecordedEvent(decision("first"));
        listener.judgeRecordedEvent(decision("second"));

        listener.stop();

        assertThat(judged).containsExactly("first", "second");
        assertThat(warnings()).isEmpty();
    }

    @Test
    void shutdownStopsJudgingInBoundedTimeAndReportsTheBeatsLeftUnjudged() throws Exception {
        var started = new CountDownLatch(1);
        var listener = listener(sessionId -> {
            started.countDown();
            new CountDownLatch(1).await(); // a judge call that outlives shutdown
            judged.add(sessionId);
        });
        listener.judgeRecordedEvent(decision("running"));
        listener.judgeRecordedEvent(decision("queued-1"));
        listener.judgeRecordedEvent(decision("queued-2"));
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

        long begin = System.nanoTime();
        listener.stop();
        Duration shutdown = Duration.ofNanos(System.nanoTime() - begin);

        assertThat(shutdown).isLessThan(JudgmentListener.SHUTDOWN_GRACE.plusSeconds(1));
        assertThat(judged).isEmpty();
        assertThat(warnings())
                .singleElement()
                .asString()
                .contains("2 queued beats were not judged", "1 in-progress judgment was interrupted");
    }

    private List<String> warnings() {

        return logs.list.stream()
                .filter(event -> event.getLevel().isGreaterOrEqual(Level.WARN))
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    private interface Verdict {
        void judge(String sessionId) throws Exception;
    }

    private static JudgmentListener listener(Verdict verdict) {
        JudgmentStateBuilder states = mock(JudgmentStateBuilder.class);
        when(states.build(any(), any())).thenAnswer(call -> {
            BeatState state = mock(BeatState.class);
            when(state.beat()).thenReturn(call.getArgument(0));

            return Optional.of(state);
        });
        Judge judge = new Judge() {
            @Override
            public Optional<Judgment> judge(BeatState state) {
                try {
                    verdict.judge(state.beat().sessionId());
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Fixture judge interrupted", ex);
                } catch (Exception ex) {
                    throw new IllegalStateException(ex);
                }

                return Optional.empty();
            }

            @Override
            public JudgeStats stats() {

                return null;
            }
        };

        return new JudgmentListener(
                new BeatFolder(new ObjectMapper(), 4_000, 12, 1_500),
                states,
                judge,
                mock(JudgmentRepository.class),
                mock(JudgmentPublication.class),
                Clock.systemUTC());
    }

    /** Decisions close their beat at once, so each capture enqueues one judgment. */
    private static EventRecorded decision(String sessionId) {
        var session = new AgentSession(
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
        var event = new AgentEvent(
                sessionId + "-decision",
                sessionId,
                "codex",
                sessionId,
                null,
                "Decision",
                "assistant",
                "Choose the smaller slice",
                null,
                null,
                null,
                Map.of(),
                Instant.parse("2026-10-15T12:00:00Z"),
                null);

        return new EventRecorded(session, event);
    }
}
