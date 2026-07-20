package dev.nathan.sbaagentic.workflow.internal.adapter.in.web;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.nathan.sbaagentic.task.ClaimTaskRequest;
import dev.nathan.sbaagentic.task.CompleteTaskRequest;
import dev.nathan.sbaagentic.task.CreateAnnotationRequest;
import dev.nathan.sbaagentic.task.CreateSpecRequest;
import dev.nathan.sbaagentic.task.EnqueueTaskRequest;
import dev.nathan.sbaagentic.task.TaskAnnotation;
import dev.nathan.sbaagentic.task.TaskChange;
import dev.nathan.sbaagentic.task.TaskDomainException;
import dev.nathan.sbaagentic.task.TaskErrorCode;
import dev.nathan.sbaagentic.task.TaskEvent;
import dev.nathan.sbaagentic.task.TaskQuery;
import dev.nathan.sbaagentic.task.TaskService;
import dev.nathan.sbaagentic.task.TaskSnapshot;
import dev.nathan.sbaagentic.task.TaskSpec;
import dev.nathan.sbaagentic.task.TaskStatus;
import dev.nathan.sbaagentic.task.UpdateTaskStatusRequest;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class TaskController {

    private final TaskService taskService;

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    @PostMapping("/specs")
    public TaskSpec createSpec(@RequestBody CreateSpecRequest request) {
        return taskService.createSpec(request);
    }

    @GetMapping("/specs/{specId}")
    public TaskSpec getSpec(@PathVariable String specId) {
        return taskService.getSpec(requireUuid(specId, "Spec id"));
    }

    @PostMapping("/tasks")
    public TaskChange enqueueTask(@RequestBody EnqueueTaskRequest request) {
        return taskService.enqueueTask(new EnqueueTaskRequest(
                requireUuid(request.specId(), "Spec id"),
                request.title(),
                request.lane(),
                request.priority(),
                request.actor()));
    }

    @GetMapping("/tasks")
    public List<TaskSnapshot> listTasks(
            @RequestParam(required = false) String projectKey,
            @RequestParam(required = false) String lane,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "100") int limit) {
        return taskService.listTasks(new TaskQuery(
                        optionalFilter(projectKey),
                        optionalFilter(lane),
                        optionalStatus(status)))
                .stream()
                .limit(safeTaskLimit(limit))
                .toList();
    }

    @PostMapping("/tasks/claim")
    public ResponseEntity<TaskChange> claimNextTask(@RequestBody ClaimTaskRequest request) {
        return taskService.claimNextTask(request)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PatchMapping("/tasks/{taskId}")
    public TaskChange updateTaskStatus(
            @PathVariable String taskId,
            @RequestBody TaskStatusBody request) {
        return taskService.updateTaskStatus(new UpdateTaskStatusRequest(
                requireUuid(taskId, "Task id"),
                request.actor(),
                requireStatus(request.status()),
                request.blockedReason()));
    }

    @PostMapping("/tasks/{taskId}/complete")
    public TaskChange completeTask(
            @PathVariable String taskId,
            @RequestBody CompleteTaskBody request) {
        return taskService.completeTask(new CompleteTaskRequest(
                requireUuid(taskId, "Task id"),
                request.actor(),
                request.source(),
                request.clientSessionId(),
                request.summary(),
                request.openLoops(),
                request.nextAction()));
    }

    @PostMapping("/tasks/{taskId}/annotations")
    public TaskAnnotation createAnnotation(
            @PathVariable String taskId,
            @RequestBody AnnotationBody request) {
        return taskService.createAnnotation(new CreateAnnotationRequest(
                requireUuid(taskId, "Task id"),
                request.actor(),
                request.kind(),
                request.text(),
                request.dataJson()));
    }

    @GetMapping("/tasks/{taskId}/events")
    public List<TaskEvent> taskEvents(@PathVariable String taskId) {
        return taskService.getTaskEvents(requireUuid(taskId, "Task id"));
    }

    public record TaskStatusBody(String actor, String status, String blockedReason) {
    }

    public record AnnotationBody(String actor, String kind, String text, Map<String, Object> dataJson) {
    }

    public record CompleteTaskBody(
            String actor,
            String source,
            String clientSessionId,
            String summary,
            List<String> openLoops,
            String nextAction) {
    }

    private static int safeTaskLimit(int limit) {
        return Math.max(1, Math.min(limit, 250));
    }

    private static String optionalFilter(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static TaskStatus optionalStatus(String value) {
        return value == null || value.isBlank() ? null : parseStatus(value);
    }

    private static TaskStatus requireStatus(String value) {
        if (value == null || value.isBlank()) {
            throw validation("Task status is required");
        }
        return parseStatus(value);
    }

    private static TaskStatus parseStatus(String value) {
        try {
            return TaskStatus.fromValue(value);
        }
        catch (IllegalArgumentException ex) {
            throw validation("Unknown task status: " + value);
        }
    }

    private static String requireUuid(String value, String label) {
        if (value == null || value.isBlank()) {
            throw validation(label + " is required");
        }
        String normalized = value.strip();
        try {
            UUID parsed = UUID.fromString(normalized);
            if (!parsed.toString().equalsIgnoreCase(normalized)) {
                throw new IllegalArgumentException("Noncanonical UUID");
            }
            return parsed.toString();
        }
        catch (IllegalArgumentException ex) {
            throw validation(label + " must be a UUID");
        }
    }

    private static TaskDomainException validation(String message) {
        return new TaskDomainException(TaskErrorCode.VALIDATION_FAILED, message, null, null, null);
    }
}
