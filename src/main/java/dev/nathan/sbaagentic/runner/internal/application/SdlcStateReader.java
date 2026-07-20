package dev.nathan.sbaagentic.runner.internal.application;

import java.io.File;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import dev.nathan.sbaagentic.runner.internal.client.blackbox.BlackBoxApiClient;
import dev.nathan.sbaagentic.runner.internal.client.blackbox.Task;
import dev.nathan.sbaagentic.runner.internal.client.blackbox.TaskEvent;
import dev.nathan.sbaagentic.runner.internal.client.blackbox.TaskEventType;
import dev.nathan.sbaagentic.runner.internal.client.blackbox.TaskSnapshot;
import dev.nathan.sbaagentic.runner.internal.client.blackbox.TaskStatus;

public class SdlcStateReader {

    private static final String WORKER_ACTOR = "blackbox-runner-worker";

    private final BlackBoxApiClient apiClient;

    public SdlcStateReader(BlackBoxApiClient apiClient) {
        this.apiClient = apiClient;
    }

    public boolean hasApprovedPlan(String specId, String actorId) {
        Optional<Task> planTask = latestTask(specId, "sdlc:plan", TaskStatus.DONE);
        if (planTask.isEmpty()) {
            return false;
        }
        List<TaskEvent> events = safeList(apiClient.taskEvents(planTask.orElseThrow().id()));
        boolean hasPlan = events.stream().anyMatch(event -> event != null
                && event.type() == TaskEventType.NOTE
                && WORKER_ACTOR.equals(event.actor())
                && event.detail() != null
                && "plan".equals(event.detail().get("kind"))
                && !isBlank(stringValue(event.detail().get("text"))));
        if (!hasPlan) {
            return false;
        }
        boolean rejected = events.stream().anyMatch(event -> {
            Map<?, ?> data = dataJson(event);
            boolean approvalRejected = isAnnotation(event, "approval")
                    && "plan".equals(stringValue(data.get("stage")))
                    && "reject".equals(stringValue(data.get("decision")));
            boolean rejectionRecorded = event != null
                    && Objects.equals(actorId, event.actor())
                    && isAnnotation(event, "progress")
                    && "rejection_recorded".equals(stringValue(data.get("sdlc")));
            return approvalRejected || rejectionRecorded;
        });
        if (rejected) {
            return false;
        }
        for (int index = events.size() - 1; index >= 0; index--) {
            TaskEvent event = events.get(index);
            if (!isAnnotation(event, "approval")) {
                continue;
            }
            Map<?, ?> data = dataJson(event);
            return "plan".equals(stringValue(data.get("stage")))
                    && "approve".equals(stringValue(data.get("decision")));
        }
        return false;
    }

    public Optional<BuildArtifact> buildArtifact(String taskId, String actorId) {
        List<TaskEvent> events = safeList(apiClient.taskEvents(taskId));
        for (int index = events.size() - 1; index >= 0; index--) {
            TaskEvent event = events.get(index);
            if (event == null
                    || event.type() != TaskEventType.NOTE
                    || event.detail() == null
                    || !Objects.equals(actorId, event.actor())
                    || !"progress".equals(event.detail().get("kind"))) {
                continue;
            }
            Object dataValue = event.detail().get("dataJson");
            if (!(dataValue instanceof Map<?, ?> data)) {
                continue;
            }
            String branch = stringValue(data.get("branch"));
            String worktree = stringValue(data.get("worktree"));
            if (!isBlank(branch) && !isBlank(worktree) && Path.of(worktree).isAbsolute()) {
                return Optional.of(new BuildArtifact(branch, new File(worktree).getAbsoluteFile()));
            }
        }
        return Optional.empty();
    }

    public boolean hasWorkerDone(String taskId) {
        return safeList(apiClient.taskEvents(taskId)).stream().anyMatch(event -> {
            if (event == null
                    || event.type() != TaskEventType.NOTE
                    || !WORKER_ACTOR.equals(event.actor())
                    || event.detail() == null
                    || !"progress".equals(event.detail().get("kind"))) {
                return false;
            }
            Map<?, ?> data = dataJson(event);
            return "worker_done".equals(stringValue(data.get("event")))
                    && "done".equals(stringValue(data.get("outcome")));
        });
    }

    public Optional<Task> latestTask(String specId, String lane, TaskStatus status) {
        return safeList(apiClient.listTasks(null, lane)).stream()
                .map(TaskSnapshot::task)
                .filter(Objects::nonNull)
                .filter(task -> specId.equals(task.specId()))
                .filter(task -> lane.equals(task.lane()))
                .filter(task -> status == null || task.status() == status)
                .max(Comparator.comparing(
                        task -> task.updatedAt() == null ? task.createdAt() : task.updatedAt(),
                        Comparator.nullsFirst(Comparator.naturalOrder())));
    }

    public Optional<String> latestWorkerAnnotationText(String taskId, String kind, Instant since) {
        List<TaskEvent> events = safeList(apiClient.taskEvents(taskId));
        for (int index = events.size() - 1; index >= 0; index--) {
            TaskEvent event = events.get(index);
            if (event == null
                    || event.type() != TaskEventType.NOTE
                    || event.detail() == null
                    || !WORKER_ACTOR.equals(event.actor())
                    || event.observedAt() == null
                    || (since != null && event.observedAt().isBefore(since))
                    || !kind.equals(event.detail().get("kind"))) {
                continue;
            }
            String text = stringValue(event.detail().get("text"));
            if (!isBlank(text)) {
                return Optional.of(text);
            }
        }
        return Optional.empty();
    }

    private static boolean isAnnotation(TaskEvent event, String kind) {
        return event != null
                && event.type() == TaskEventType.NOTE
                && event.detail() != null
                && kind.equals(stringValue(event.detail().get("kind")));
    }

    private static Map<?, ?> dataJson(TaskEvent event) {
        if (event == null || event.detail() == null) {
            return Map.of();
        }
        Object data = event.detail().get("dataJson");
        return data instanceof Map<?, ?> map ? map : Map.of();
    }

    private static String stringValue(Object value) {
        return value instanceof String text ? text : null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    public record BuildArtifact(String branch, File worktree) {
    }
}
