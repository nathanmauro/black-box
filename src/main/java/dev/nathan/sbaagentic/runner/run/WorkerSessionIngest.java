package dev.nathan.sbaagentic.runner.run;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nathan.sbaagentic.event.IngestResponse;
import dev.nathan.sbaagentic.runner.BlackBoxApiClient;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Component;

/**
 * Imports a worker rollout periodically while a run is active and once more when it ends. Per-task
 * line offsets keep incremental and completion-time passes from reposting events already ingested
 * through the generic {@code /api/events} endpoint.
 */
@Component
public class WorkerSessionIngest {

    private static final Logger log = LoggerFactory.getLogger(WorkerSessionIngest.class);
    private static final int MAX_EVENTS = 2_000;

    private final BlackBoxApiClient apiClient;
    private final ObjectMapper objectMapper;
    private final Map<String, IngestState> stateByTaskId = new ConcurrentHashMap<>();

    public WorkerSessionIngest(BlackBoxApiClient apiClient, ObjectMapper objectMapper) {
        this.apiClient = apiClient;
        this.objectMapper = objectMapper;
    }

    public Optional<String> ingestAndLink(
            File worktreeDir,
            String taskId,
            String actorId,
            String orchestratorSessionId) {
        Path sessionsRoot = Path.of(System.getProperty("user.home"), ".codex", "sessions");
        return ingestAndLink(
                sessionsRoot, worktreeDir, taskId, actorId, orchestratorSessionId);
    }

    synchronized Optional<String> ingestAndLink(
            Path sessionsRoot,
            File worktreeDir,
            String taskId,
            String actorId,
            String orchestratorSessionId) {
        try {
            Optional<String> workerSessionId = ingestIncremental(
                    sessionsRoot, worktreeDir, taskId, actorId, orchestratorSessionId);
            if (workerSessionId.isEmpty()
                    && findLatestMatchingRollout(
                                    sessionsRoot, canonicalPath(worktreeDir))
                            .isEmpty()) {
                log.warn(
                        "No Codex rollout found for worker task {} at {}",
                        taskId,
                        canonicalPath(worktreeDir));
                annotateProgressBestEffort(
                        taskId, actorId, "Worker session ingest found no matching Codex rollout.");
            }
            return workerSessionId;
        }
        finally {
            stateByTaskId.remove(taskId);
        }
    }

    public Optional<String> ingestIncremental(
            File worktreeDir,
            String taskId,
            String actorId,
            String orchestratorSessionId) {
        Path sessionsRoot = Path.of(System.getProperty("user.home"), ".codex", "sessions");
        return ingestIncremental(
                sessionsRoot, worktreeDir, taskId, actorId, orchestratorSessionId);
    }

    synchronized Optional<String> ingestIncremental(
            Path sessionsRoot,
            File worktreeDir,
            String taskId,
            String actorId,
            String orchestratorSessionId) {
        IngestState existingState = stateByTaskId.get(taskId);
        if (existingState != null) {
            ingestNewLines(existingState, taskId);
            return Optional.of(existingState.workerSessionId);
        }

        String canonicalWorktree = canonicalPath(worktreeDir);
        Optional<Path> rollout = findLatestMatchingRollout(sessionsRoot, canonicalWorktree);
        if (rollout.isEmpty()) {
            log.debug("No Codex rollout found yet for worker task {} at {}", taskId, canonicalWorktree);
            return Optional.empty();
        }
        return ingestRolloutIncremental(
                rollout.orElseThrow(), taskId, actorId, orchestratorSessionId);
    }

    Optional<Path> findLatestMatchingRollout(Path sessionsRoot, String worktreeCanonicalPath) {
        if (sessionsRoot == null || !Files.isDirectory(sessionsRoot)) {
            return Optional.empty();
        }
        try (Stream<Path> paths = Files.walk(sessionsRoot)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".jsonl"))
                    .filter(path -> rolloutMatches(path, worktreeCanonicalPath))
                    .max(Comparator.comparing(this::lastModified));
        }
        catch (IOException ex) {
            log.warn("Unable to scan Codex rollout directory {}", sessionsRoot, ex);
            return Optional.empty();
        }
    }

    private boolean rolloutMatches(Path path, String worktreeCanonicalPath) {
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String firstLine = reader.readLine();
            if (firstLine == null) {
                return false;
            }
            JsonNode root = objectMapper.readTree(firstLine);
            if (!"session_meta".equals(root.path("type").asText())) {
                return false;
            }
            String cwd = textOrNull(root.path("payload").path("cwd"));
            return cwd != null
                    && canonicalPath(new File(cwd)).equals(worktreeCanonicalPath);
        }
        catch (IOException | RuntimeException ex) {
            log.debug("Skipping unreadable Codex rollout candidate {}", path, ex);
            return false;
        }
    }

    private Optional<String> ingestRolloutIncremental(
            Path rollout,
            String taskId,
            String actorId,
            String orchestratorSessionId) {
        try (BufferedReader reader = Files.newBufferedReader(rollout, StandardCharsets.UTF_8)) {
            String firstLine = reader.readLine();
            if (firstLine == null) {
                return Optional.empty();
            }
            JsonNode sessionMeta = objectMapper.readTree(firstLine);
            String clientSessionId = textOrNull(sessionMeta.path("payload").path("session_id"));
            String cwd = textOrNull(sessionMeta.path("payload").path("cwd"));
            if (clientSessionId == null || cwd == null) {
                log.warn("Matching Codex rollout {} has invalid session metadata", rollout);
                annotateProgressBestEffort(
                        taskId, actorId, "Worker session ingest found invalid Codex session metadata.");
                return Optional.empty();
            }

            IngestResponse firstResponse = postLine(sessionMeta, clientSessionId, cwd);
            String workerSessionId = firstResponse.sessionId();
            apiClient.createSessionLink(
                    orchestratorSessionId, workerSessionId, "spawned", taskId);
            apiClient.annotate(
                    taskId,
                    actorId,
                    "worker_session",
                    "Worker session ingested.",
                    Map.of("sessionId", workerSessionId));

            IngestState state = new IngestState(
                    rollout, clientSessionId, cwd, workerSessionId, 1, 1);
            stateByTaskId.put(taskId, state);
            ingestRemainingLines(reader, state, taskId);
            return Optional.of(workerSessionId);
        }
        catch (IOException ex) {
            throw new IllegalStateException("Unable to ingest Codex rollout " + rollout, ex);
        }
    }

    private void ingestNewLines(IngestState state, String taskId) {
        try (BufferedReader reader = Files.newBufferedReader(state.rollout, StandardCharsets.UTF_8)) {
            for (long skipped = 0; skipped < state.linesConsumed; skipped++) {
                if (reader.readLine() == null) {
                    return;
                }
            }
            ingestRemainingLines(reader, state, taskId);
        }
        catch (IOException ex) {
            throw new IllegalStateException("Unable to ingest Codex rollout " + state.rollout, ex);
        }
    }

    private void ingestRemainingLines(
            BufferedReader reader, IngestState state, String taskId) throws IOException {
        String line;
        while (state.eventsIngested < MAX_EVENTS && (line = reader.readLine()) != null) {
            if (line.isBlank()) {
                state.linesConsumed++;
                continue;
            }
            try {
                JsonNode parsed = objectMapper.readTree(line);
                postLine(parsed, state.clientSessionId, state.cwd);
                state.linesConsumed++;
                state.eventsIngested++;
            }
            catch (JsonProcessingException ex) {
                state.linesConsumed++;
                log.warn("Skipping malformed Codex rollout line in {}", state.rollout, ex);
            }
        }
        if (state.eventsIngested == MAX_EVENTS
                && !state.truncationWarned
                && reader.readLine() != null) {
            log.warn("Codex worker ingest for task {} truncated at {} events", taskId, MAX_EVENTS);
            state.truncationWarned = true;
        }
    }

    private IngestResponse postLine(JsonNode root, String clientSessionId, String cwd) {
        JsonNode payload = root.path("payload");
        return apiClient.postEvent(
                "codex",
                clientSessionId,
                null,
                textOrDefault(root.path("type"), "unknown"),
                textOrNull(payload.path("role")),
                extractText(payload),
                cwd,
                null,
                null,
                null,
                Map.of("title", "Codex worker session", "raw", root),
                parseObservedAt(root.path("timestamp")));
    }

    private void annotateProgressBestEffort(String taskId, String actorId, String text) {
        try {
            apiClient.annotate(taskId, actorId, "progress", text, null);
        }
        catch (RuntimeException ex) {
            log.warn("Unable to annotate worker session ingest result for task {}", taskId, ex);
        }
    }

    private static String extractText(JsonNode payload) {
        String direct = textOrNull(payload.path("text"));
        if (direct != null) {
            return direct;
        }
        JsonNode content = payload.path("content");
        if (!content.isArray()) {
            return null;
        }
        StringBuilder joined = new StringBuilder();
        for (JsonNode item : content) {
            String text = textOrNull(item.path("text"));
            if (text == null) {
                continue;
            }
            if (!joined.isEmpty()) {
                joined.append('\n');
            }
            joined.append(text);
        }
        return joined.isEmpty() ? null : joined.toString();
    }

    private static Instant parseObservedAt(JsonNode timestamp) {
        String value = textOrNull(timestamp);
        if (value == null) {
            return Instant.now();
        }
        try {
            return Instant.parse(value);
        }
        catch (DateTimeParseException ex) {
            return Instant.now();
        }
    }

    private java.nio.file.attribute.FileTime lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path);
        }
        catch (IOException ex) {
            return java.nio.file.attribute.FileTime.fromMillis(0);
        }
    }

    private static String canonicalPath(File file) {
        try {
            return file.getCanonicalPath();
        }
        catch (IOException ex) {
            throw new IllegalStateException("Unable to canonicalize path " + file, ex);
        }
    }

    private static String textOrDefault(JsonNode value, String fallback) {
        String text = textOrNull(value);
        return text == null ? fallback : text;
    }

    private static String textOrNull(JsonNode value) {
        return value == null || value.isMissingNode() || value.isNull() || !value.isTextual()
                ? null
                : value.textValue();
    }

    private static final class IngestState {

        private final Path rollout;
        private final String clientSessionId;
        private final String cwd;
        private final String workerSessionId;
        private long linesConsumed;
        private int eventsIngested;
        private boolean truncationWarned;

        private IngestState(
                Path rollout,
                String clientSessionId,
                String cwd,
                String workerSessionId,
                long linesConsumed,
                int eventsIngested) {
            this.rollout = rollout;
            this.clientSessionId = clientSessionId;
            this.cwd = cwd;
            this.workerSessionId = workerSessionId;
            this.linesConsumed = linesConsumed;
            this.eventsIngested = eventsIngested;
        }
    }
}
