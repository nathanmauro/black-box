package dev.nathan.sbaagentic.runner;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nathan.sbaagentic.event.IngestResponse;
import dev.nathan.sbaagentic.runner.process.TmuxController;
import dev.nathan.sbaagentic.runner.run.ActiveRunRegistry;
import dev.nathan.sbaagentic.task.TaskChange;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Component;

@Component
public class RunnerDaemon {

    private static final Logger log = LoggerFactory.getLogger(RunnerDaemon.class);
    private static final long FAILURE_BACKOFF_MILLIS = 2_000;
    private static final String ACTOR_ID = "blackbox-runner";

    private final BlackBoxApiClient apiClient;
    private final CrashRecovery crashRecovery;
    private final GateCycle gateCycle;
    private final AutoCycle autoCycle;
    private final ObjectMapper objectMapper;
    private final TmuxController tmux;
    private final ActiveRunRegistry activeRunRegistry;
    private final AtomicBoolean running = new AtomicBoolean();

    private volatile long lastWakeAtMillis;
    private volatile SseStreamReader sseReader;

    public RunnerDaemon(
            BlackBoxApiClient apiClient,
            CrashRecovery crashRecovery,
            GateCycle gateCycle,
            AutoCycle autoCycle,
            ObjectMapper objectMapper,
            TmuxController tmux,
            ActiveRunRegistry activeRunRegistry) {
        this.apiClient = apiClient;
        this.crashRecovery = crashRecovery;
        this.gateCycle = gateCycle;
        this.autoCycle = autoCycle;
        this.objectMapper = objectMapper;
        this.tmux = tmux;
        this.activeRunRegistry = activeRunRegistry;
    }

    public void run(RunnerConfig config) {
        Path lockFile = Path.of(System.getProperty("user.home"), ".blackbox", "runner.lock");
        Optional<RunnerInstanceLock> acquiredLock;
        try {
            acquiredLock = RunnerInstanceLock.tryAcquire(lockFile);
        }
        catch (IOException ex) {
            log.error(
                    "Unable to acquire the blackbox-runner instance lock at {}; refusing to start",
                    lockFile,
                    ex);
            return;
        }
        if (acquiredLock.isEmpty()) {
            log.error(
                    "Another blackbox-runner instance appears to already be running; refusing to "
                            + "start this instance so crash recovery cannot corrupt its in-flight claims.");
            return;
        }
        RunnerInstanceLock instanceLock = acquiredLock.orElseThrow();

        String actorId = ACTOR_ID;
        int concurrency = Math.max(1, config.concurrency());
        AtomicInteger activeAutoRuns = new AtomicInteger();
        ExecutorService autoPool = Executors.newFixedThreadPool(concurrency);
        SseStreamReader reader = null;

        running.set(true);
        try {
            crashRecovery.reconcile(config, actorId);

            String startupUuid = UUID.randomUUID().toString();
            String clientSessionId = "blackbox-runner-" + startupUuid;
            IngestResponse response = apiClient.postEvent(
                    "cli",
                    clientSessionId,
                    null,
                    "RunnerStarted",
                    "assistant",
                    "Black Box runner daemon started.",
                    null,
                    null,
                    null,
                    null,
                    Map.of("title", "Black Box runner"),
                    Instant.now());
            String orchestratorSessionId = response.sessionId();
            log.info("Black Box runner orchestrator session: {}", orchestratorSessionId);

            reader = new SseStreamReader(apiClient, this::onSseFrame);
            sseReader = reader;
            reader.start();

            while (running.get()) {
                try {
                    Optional<TaskChange> gateTask = apiClient.claimTask("gate", actorId);
                    if (gateTask.isPresent()) {
                        gateCycle.evaluate(gateTask.get(), config, actorId);
                        continue;
                    }

                    if (activeAutoRuns.get() < concurrency) {
                        Optional<TaskChange> autoTask = apiClient.claimTask("auto", actorId);
                        if (autoTask.isPresent()) {
                            activeAutoRuns.incrementAndGet();
                            try {
                                autoPool.submit(() -> {
                                    try {
                                        autoCycle.execute(
                                                autoTask.get(), config, actorId, orchestratorSessionId);
                                    }
                                    finally {
                                        activeAutoRuns.decrementAndGet();
                                    }
                                });
                            }
                            catch (RuntimeException ex) {
                                activeAutoRuns.decrementAndGet();
                                throw ex;
                            }
                            continue;
                        }
                    }

                    if (!sleep(1_000 + ThreadLocalRandom.current().nextInt(1_500))) {
                        break;
                    }
                }
                catch (RuntimeException ex) {
                    log.warn("Black Box runner cycle failed; retrying", ex);
                    if (!sleep(FAILURE_BACKOFF_MILLIS)) {
                        break;
                    }
                }
            }
        }
        finally {
            running.set(false);
            if (reader != null) {
                reader.stop();
            }
            autoPool.shutdownNow();
            sseReader = null;
            try {
                instanceLock.close();
            }
            catch (IOException ex) {
                log.warn("Unable to release the blackbox-runner instance lock at {}", lockFile, ex);
            }
        }
    }

    public void stop() {
        running.set(false);
        SseStreamReader reader = sseReader;
        if (reader != null) {
            reader.stop();
        }
    }

    private void onSseFrame(String eventName, String jsonData) {
        lastWakeAtMillis = System.currentTimeMillis();
        if (!"task.note".equals(eventName)) {
            return;
        }
        try {
            JsonNode root = objectMapper.readTree(jsonData);
            JsonNode annotation = root.path("annotation");
            JsonNode task = root.path("task");
            if (!"steer".equals(annotation.path("kind").asText())
                    || !ACTOR_ID.equals(task.path("claimedBy").asText())) {
                return;
            }
            String taskId = task.path("id").asText();
            String steerText = annotation.path("text").asText();
            if (taskId.isBlank() || steerText.isBlank()) {
                return;
            }
            activeRunRegistry.tmuxSessionFor(taskId).ifPresent(tmuxSessionName -> {
                tmux.sendKeys(tmuxSessionName, steerText);
                apiClient.annotate(
                        taskId,
                        ACTOR_ID,
                        "progress",
                        "Steering injected into active run.",
                        null);
            });
        }
        catch (JsonProcessingException ex) {
            log.warn("Unable to parse task.note SSE frame for runner steering", ex);
        }
        catch (RuntimeException ex) {
            log.warn("Unable to inject steering from task.note SSE frame", ex);
        }
    }

    private static boolean sleep(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
