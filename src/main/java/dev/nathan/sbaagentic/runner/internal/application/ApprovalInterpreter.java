package dev.nathan.sbaagentic.runner.internal.application;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import dev.nathan.sbaagentic.runner.internal.client.blackbox.BlackBoxApiClient;
import dev.nathan.sbaagentic.runner.internal.client.blackbox.Task;
import dev.nathan.sbaagentic.runner.internal.client.blackbox.TaskEvent;
import dev.nathan.sbaagentic.runner.internal.client.blackbox.TaskEventType;

public class ApprovalInterpreter {

    public static final String SHIPPED = "shipped";
    public static final String REJECTION_RECORDED = "rejection_recorded";
    private static final String WORKER_ACTOR = "blackbox-runner-worker";

    private final BlackBoxApiClient apiClient;

    public ApprovalInterpreter(BlackBoxApiClient apiClient) {
        this.apiClient = apiClient;
    }

    public Optional<Approval> latestApproval(List<TaskEvent> events, String expectedStage) {
        for (int index = events.size() - 1; index >= 0; index--) {
            TaskEvent event = events.get(index);
            if (!isAnnotation(event, "approval")) {
                continue;
            }
            return parseApproval(event).filter(approval -> expectedStage.equals(approval.stage()));
        }
        return Optional.empty();
    }

    public Optional<Approval> latestRejection(List<TaskEvent> events, String expectedStage) {
        for (int index = events.size() - 1; index >= 0; index--) {
            TaskEvent event = events.get(index);
            Optional<Approval> approval = parseApproval(event);
            if (approval.isPresent()
                    && expectedStage.equals(approval.orElseThrow().stage())
                    && "reject".equals(approval.orElseThrow().decision())) {
                return approval;
            }
        }
        return Optional.empty();
    }

    public void recordRejection(Task task, Approval approval, String actorId) {
        String feedback = isBlank(approval.feedback()) ? "No feedback provided." : approval.feedback();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sdlc", REJECTION_RECORDED);
        data.put("approvalId", approval.id());
        data.put("decision", "reject");
        data.put("stage", approval.stage());
        data.put("feedback", feedback);
        apiClient.annotate(
                task.id(), actorId, "progress",
                "SDLC " + approval.stage() + " rejected: " + feedback,
                data);
    }

    public Optional<ShipMarker> latestShipMarker(List<TaskEvent> events, String actorId) {
        for (int index = events.size() - 1; index >= 0; index--) {
            TaskEvent event = events.get(index);
            if (!isRunnerProgress(event, actorId)) {
                continue;
            }
            Map<?, ?> data = dataJson(event);
            if (!SHIPPED.equals(stringValue(data.get("sdlc")))) {
                continue;
            }
            return Optional.of(new ShipMarker(
                    stringValue(data.get("status")),
                    stringValue(data.get("branch")),
                    stringValue(data.get("worktree"))));
        }
        return Optional.empty();
    }

    public boolean hasRunnerMarker(List<TaskEvent> events, String actorId, String marker) {
        return events.stream()
                .filter(event -> isRunnerProgress(event, actorId))
                .map(ApprovalInterpreter::dataJson)
                .anyMatch(data -> marker.equals(stringValue(data.get("sdlc"))));
    }

    public Optional<String> latestWorkerAnnotationText(List<TaskEvent> events, String kind) {
        for (int index = events.size() - 1; index >= 0; index--) {
            TaskEvent event = events.get(index);
            if (event != null && WORKER_ACTOR.equals(event.actor()) && isAnnotation(event, kind)) {
                String text = stringValue(event.detail().get("text"));
                if (!isBlank(text)) {
                    return Optional.of(text);
                }
            }
        }
        return Optional.empty();
    }

    public boolean hasWorkerDone(List<TaskEvent> events) {
        return events.stream().anyMatch(event -> {
            if (event == null
                    || !WORKER_ACTOR.equals(event.actor())
                    || !isAnnotation(event, "progress")) {
                return false;
            }
            Map<?, ?> data = dataJson(event);
            return "worker_done".equals(stringValue(data.get("event")))
                    && "done".equals(stringValue(data.get("outcome")));
        });
    }

    private Optional<Approval> parseApproval(TaskEvent event) {
        if (!isAnnotation(event, "approval")) {
            return Optional.empty();
        }
        Map<?, ?> data = dataJson(event);
        String decision = stringValue(data.get("decision"));
        String stage = stringValue(data.get("stage"));
        if ((!"approve".equals(decision) && !"reject".equals(decision))
                || (!"plan".equals(stage) && !"review".equals(stage))) {
            return Optional.empty();
        }
        return Optional.of(new Approval(
                event.id(), decision, stage, stringValue(data.get("feedback"))));
    }

    private static boolean isRunnerProgress(TaskEvent event, String actorId) {
        return event != null && Objects.equals(actorId, event.actor()) && isAnnotation(event, "progress");
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
        return value instanceof String string ? string : null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public record Approval(String id, String decision, String stage, String feedback) {
    }

    public record ShipMarker(String status, String branch, String worktree) {
    }
}
