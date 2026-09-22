package dev.nathan.sbaagentic.judgment.internal.application;

import dev.nathan.sbaagentic.judgment.JudgmentAppended;
import dev.nathan.sbaagentic.judgment.JudgmentPublication;
import dev.nathan.sbaagentic.judgment.internal.application.port.Judge;
import dev.nathan.sbaagentic.judgment.internal.application.port.JudgeStats;
import dev.nathan.sbaagentic.judgment.internal.application.port.JudgmentRepository;
import dev.nathan.sbaagentic.judgment.internal.domain.Beat;
import dev.nathan.sbaagentic.judgment.internal.domain.BeatState;
import dev.nathan.sbaagentic.recording.EventRecorded;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "sba.judge.enabled", havingValue = "true")
public class JudgmentListener {

    private static final int QUEUE_CAPACITY = 256;
    private static final int TRAIL_CAPACITY = 60;
    private static final Logger log = LoggerFactory.getLogger(JudgmentListener.class);

    private final BeatFolder folder;
    private final JudgmentStateBuilder stateBuilder;
    private final Judge judge;
    private final JudgmentRepository repository;
    private final JudgmentPublication publication;
    private final Clock clock;
    private final Map<String, ArrayDeque<String>> trails = new java.util.concurrent.ConcurrentHashMap<>();
    private final AtomicLong dropped = new AtomicLong();
    private final ThreadPoolExecutor executor;

    public JudgmentListener(
            BeatFolder folder,
            JudgmentStateBuilder stateBuilder,
            Judge judge,
            JudgmentRepository repository,
            JudgmentPublication publication,
            Clock clock) {
        this.folder = folder;
        this.stateBuilder = stateBuilder;
        this.judge = judge;
        this.repository = repository;
        this.publication = publication;
        this.clock = clock;
        this.executor = new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY),
                runnable -> {
                    Thread thread = new Thread(runnable, "blackbox-judge");
                    thread.setDaemon(true);

                    return thread;
                },
                (runnable, pool) -> {
                    if (pool.getQueue().poll() != null) {
                        long count = dropped.incrementAndGet();
                        log.warn("Dropped oldest queued judgment beat; dropped={}", count);
                    }
                    if (!pool.getQueue().offer(runnable)) {
                        long count = dropped.incrementAndGet();
                        log.warn("Dropped newest judgment beat; dropped={}", count);
                    }
                });
    }

    @EventListener
    @Order(35)
    public void judgeRecordedEvent(EventRecorded recorded) {
        if (recorded == null || recorded.event() == null) {

            return;
        }
        BeatFolder.FoldResult result = folder.fold(recorded.event());
        result.closed().ifPresent(this::enqueue);
        result.standalone().ifPresent(this::enqueue);
    }

    @Scheduled(fixedDelayString = "${sba.judge.beat.gap-ms:4000}", initialDelayString = "${sba.judge.beat.gap-ms:4000}")
    public void flushQuietBeats() {
        for (Beat beat : folder.flushQuiet(clock.instant())) {
            enqueue(beat);
        }
    }

    public int queued() {

        return executor.getQueue().size();
    }

    public long dropped() {

        return dropped.get();
    }

    public JudgeStats judgeStats() {

        return judge.stats();
    }

    private void enqueue(Beat beat) {
        executor.execute(() -> process(beat));
    }

    private void process(Beat beat) {
        try {
            Optional<BeatState> state = stateBuilder.build(beat, trails);
            if (state.isEmpty()) {

                return;
            }
            Optional<Judgment> judgment = judge.judge(state.get());
            if (judgment.isEmpty()) {

                return;
            }
            repository.saveForBeat(beat, judgment.get());
            publish(beat, judgment.get());
        } catch (RuntimeException ex) {
            log.warn("Judgment processing failed for beat {}", beat.id(), ex);
        } finally {
            remember(beat);
        }
    }

    private void publish(Beat beat, Judgment judgment) {
        publication.judgmentAppended(new JudgmentAppended(
                beat.eventIds(),
                beat.sessionId(),
                beat.id(),
                judgment.phase(),
                judgment.salience(),
                judgment.novelty(),
                judgment.human(),
                judgment.kin(),
                judgment.judge(),
                judgment.model(),
                judgment.version(),
                judgment.judgedAt() == null
                        ? Instant.now().toString()
                        : judgment.judgedAt().toString()));
    }

    private void remember(Beat beat) {
        trails.compute(beat.sessionId(), (ignored, existing) -> {
            ArrayDeque<String> titles = existing == null ? new ArrayDeque<>() : existing;
            titles.addLast(beat.title());
            while (titles.size() > TRAIL_CAPACITY) {
                titles.removeFirst();
            }

            return titles;
        });
    }

    @PreDestroy
    void stop() {
        executor.shutdownNow();
    }
}
