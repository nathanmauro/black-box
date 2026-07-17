package dev.nathan.sbaagentic.runner;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nathan.sbaagentic.task.SpecStatus;
import dev.nathan.sbaagentic.task.Task;
import dev.nathan.sbaagentic.task.TaskChange;
import dev.nathan.sbaagentic.task.TaskSnapshot;
import dev.nathan.sbaagentic.task.TaskSpec;
import dev.nathan.sbaagentic.task.TaskStatus;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SdlcTaskChainerTest {

    @Test
    void concurrentSuccessorCreationEnqueuesExactlyOnce() throws Exception {
        RecordingApiClient apiClient = new RecordingApiClient();
        SdlcTaskChainer chainer = new SdlcTaskChainer(apiClient);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(2);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Runnable ensureReview = () -> {
            try {
                ready.countDown();
                start.await();
                chainer.ensureTask(
                        "spec-1", "Story task", "sdlc:review", 10, "blackbox-runner");
            }
            catch (Throwable ex) {
                failure.compareAndSet(null, ex);
            }
            finally {
                finished.countDown();
            }
        };
        Thread first = new Thread(ensureReview, "sdlc-chain-build");
        Thread second = new Thread(ensureReview, "sdlc-chain-reconcile");
        first.start();
        second.start();

        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        assertThat(finished.await(5, TimeUnit.SECONDS)).isTrue();
        first.join();
        second.join();

        assertThat(failure.get()).isNull();
        assertThat(apiClient.enqueueCount).hasValue(1);
        assertThat(apiClient.tasks).singleElement().satisfies(snapshot -> {
            assertThat(snapshot.task().specId()).isEqualTo("spec-1");
            assertThat(snapshot.task().lane()).isEqualTo("sdlc:review");
            assertThat(snapshot.task().status()).isEqualTo(TaskStatus.OPEN);
        });
    }

    private static final class RecordingApiClient extends BlackBoxApiClient {

        private static final Instant NOW = Instant.parse("2026-07-16T12:00:00Z");

        private final List<TaskSnapshot> tasks = new ArrayList<>();
        private final AtomicInteger enqueueCount = new AtomicInteger();
        private final TaskSpec spec = new TaskSpec(
                "spec-1",
                "/tmp/project",
                "Story",
                "---\nmode: sdlc\n---\n# Story\n",
                null,
                SpecStatus.ACTIVE,
                "nathan",
                NOW,
                NOW);

        private RecordingApiClient() {
            super(new ObjectMapper());
        }

        @Override
        public List<TaskSnapshot> listTasks(String status, String lane) {
            return tasks.stream()
                    .filter(snapshot -> status == null
                            || status.equals(snapshot.task().status().value()))
                    .filter(snapshot -> lane == null || lane.equals(snapshot.task().lane()))
                    .toList();
        }

        @Override
        public TaskChange enqueueTask(
                String specId, String title, String lane, int priority, String actor) {
            int sequence = enqueueCount.incrementAndGet();
            Task task = new Task(
                    "successor-" + sequence,
                    specId,
                    spec.projectKey(),
                    title,
                    lane,
                    TaskStatus.OPEN,
                    priority,
                    actor,
                    null,
                    null,
                    null,
                    NOW,
                    NOW);
            tasks.add(new TaskSnapshot(task, spec));
            return null;
        }
    }
}
