package dev.nathan.sbaagentic.workflow;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.nathan.sbaagentic.task.ClaimTaskRequest;
import dev.nathan.sbaagentic.task.CompleteTaskRequest;
import dev.nathan.sbaagentic.task.CreateSpecRequest;
import dev.nathan.sbaagentic.task.EnqueueTaskRequest;
import dev.nathan.sbaagentic.task.TaskChange;
import dev.nathan.sbaagentic.task.TaskDomainException;
import dev.nathan.sbaagentic.task.TaskErrorCode;
import dev.nathan.sbaagentic.task.TaskQuery;
import dev.nathan.sbaagentic.task.TaskService;
import dev.nathan.sbaagentic.task.TaskSnapshot;
import dev.nathan.sbaagentic.task.TaskSpec;
import dev.nathan.sbaagentic.task.TaskStatus;
import dev.nathan.sbaagentic.task.UpdateTaskStatusRequest;

import org.springframework.stereotype.Component;

/**
 * Transitional feature implementation for workflow MCP operations. Tool annotations remain on
 * the compatibility facade so only one set of callbacks is registered.
 */
@Component
public class WorkflowTools {

    private final TaskService taskService;

    public WorkflowTools(TaskService taskService) {
        this.taskService = taskService;
    }

    public TaskSpec createSpec(
            String projectKey,
            String title,
            String body,
            Map<String, Object> specRef,
            String actor) {
        return taskService.createSpec(new CreateSpecRequest(projectKey, title, body, specRef, actor));
    }

    public TaskChange enqueueTask(String specId, String title, String lane, int priority, String actor) {
        return taskService.enqueueTask(new EnqueueTaskRequest(
                requireUuid(specId, "Spec id"), title, lane, priority, actor));
    }

    public TaskChange claimNextTask(String lane, String agent) {
        return taskService.claimNextTask(new ClaimTaskRequest(lane, agent)).orElse(null);
    }

    public TaskChange updateTaskStatus(String taskId, String actor, String status, String blockedReason) {
        return taskService.updateTaskStatus(new UpdateTaskStatusRequest(
                requireUuid(taskId, "Task id"), actor, requireStatus(status), blockedReason));
    }

    public TaskChange completeTask(
            String taskId,
            String actor,
            String source,
            String clientSessionId,
            String summary,
            List<String> openLoops,
            String nextAction) {
        return taskService.completeTask(new CompleteTaskRequest(
                requireUuid(taskId, "Task id"),
                actor,
                source,
                clientSessionId,
                summary,
                openLoops,
                nextAction));
    }

    public List<TaskSnapshot> listTasks(String projectKey, String lane, String status, Integer limit) {
        return taskService.listTasks(new TaskQuery(
                        optionalFilter(projectKey),
                        optionalFilter(lane),
                        optionalStatus(status)))
                .stream()
                .limit(safeTaskLimit(limit))
                .toList();
    }

    public TaskSpec getSpec(String specId) {
        return taskService.getSpec(requireUuid(specId, "Spec id"));
    }

    private static int safeTaskLimit(Integer limit) {
        if (limit == null) {
            return 100;
        }
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
