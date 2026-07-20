package dev.nathan.sbaagentic.workflow.internal.adapter.in.mcp;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.nathan.sbaagentic.workflow.ClaimTaskRequest;
import dev.nathan.sbaagentic.workflow.CompleteTaskRequest;
import dev.nathan.sbaagentic.workflow.CreateSpecRequest;
import dev.nathan.sbaagentic.workflow.EnqueueTaskRequest;
import dev.nathan.sbaagentic.workflow.TaskChange;
import dev.nathan.sbaagentic.workflow.TaskDomainException;
import dev.nathan.sbaagentic.workflow.TaskErrorCode;
import dev.nathan.sbaagentic.workflow.TaskQuery;
import dev.nathan.sbaagentic.workflow.internal.application.TaskService;
import dev.nathan.sbaagentic.workflow.TaskSnapshot;
import dev.nathan.sbaagentic.workflow.TaskSpec;
import dev.nathan.sbaagentic.workflow.TaskStatus;
import dev.nathan.sbaagentic.workflow.UpdateTaskStatusRequest;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.stereotype.Component;

/**
 * MCP adapter for durable workflow tasks and specs.
 */
@Component
public class WorkflowMcpTools implements Supplier<ToolCallback[]> {

    private final TaskService taskService;
    private final ObjectMapper objectMapper;

    public WorkflowMcpTools(TaskService taskService, ObjectMapper objectMapper) {
        this.taskService = taskService;
        this.objectMapper = objectMapper;
    }

    @Override
    public ToolCallback[] get() {
        return java.util.Arrays.stream(MethodToolCallbackProvider.builder()
                        .toolObjects(this)
                        .build()
                        .getToolCallbacks())
                .map(callback -> withStructuredTaskErrors(callback, objectMapper))
                .toArray(ToolCallback[]::new);
    }

    @Tool(
            description = "Create a durable project-scoped spec whose frozen body is returned with every claimed task.",
            resultConverter = RestJsonToolCallResultConverter.class)
    public TaskSpec createSpec(
            @ToolParam(description = "Project key used to group and filter the spec's tasks.") String projectKey,
            @ToolParam(description = "Human-readable spec title.") String title,
            @ToolParam(description = "Canonical frozen spec body; agents receive this without resolving files.") String body,
            @ToolParam(required = false,
                    description = "Optional provenance object such as repo, path, and sha.") Map<String, Object> specRef,
            @ToolParam(description = "Agent or source creating the spec.") String actor) {
        return taskService.createSpec(new CreateSpecRequest(projectKey, title, body, specRef, actor));
    }

    @Tool(
            description = "Enqueue an open task under an existing spec in one required routing lane.",
            resultConverter = RestJsonToolCallResultConverter.class)
    public TaskChange enqueueTask(
            @ToolParam(description = "UUID of the frozen spec this task belongs to.") String specId,
            @ToolParam(description = "Human-readable task title.") String title,
            @ToolParam(description = "Required exact routing lane, for example codex or claude.") String lane,
            @ToolParam(description = "Higher values are claimed first; equal values are FIFO.") int priority,
            @ToolParam(description = "Agent or source enqueuing the task.") String actor) {
        return taskService.enqueueTask(new EnqueueTaskRequest(
                requireUuid(specId, "Spec id"), title, lane, priority, actor));
    }

    @Tool(
            description = "Atomically claim the highest-priority oldest open task in an exact lane. Returns empty when none exists.",
            resultConverter = RestJsonToolCallResultConverter.class)
    public TaskChange claimNextTask(
            @ToolParam(description = "Required exact lane to claim from.") String lane,
            @ToolParam(description = "Agent identity that will own the claimed task.") String agent) {
        return taskService.claimNextTask(new ClaimTaskRequest(lane, agent)).orElse(null);
    }

    @Tool(
            description = "Apply an allowed task lifecycle update: block, reset to open, or cancel.",
            resultConverter = RestJsonToolCallResultConverter.class)
    public TaskChange updateTaskStatus(
            @ToolParam(description = "UUID of the task to update.") String taskId,
            @ToolParam(description = "Agent or operator causing the transition.") String actor,
            @ToolParam(description = "Target status: blocked, open (reset), or cancelled.") String status,
            @ToolParam(required = false,
                    description = "Required nonblank reason when target status is blocked.") String blockedReason) {
        return taskService.updateTaskStatus(new UpdateTaskStatusRequest(
                requireUuid(taskId, "Task id"), actor, requireStatus(status), blockedReason));
    }

    @Tool(
            description = "Complete an owned in-progress task, capture a recallable Handoff, and link its event id.",
            resultConverter = RestJsonToolCallResultConverter.class)
    public TaskChange completeTask(
            @ToolParam(description = "UUID of the task to complete.") String taskId,
            @ToolParam(description = "Current claimant completing the task.") String actor,
            @ToolParam(description = "Source client for the completion Handoff, for example codex.") String source,
            @ToolParam(description = "Real source client session id for the completion Handoff.") String clientSessionId,
            @ToolParam(description = "What was completed and where the work stands.") String summary,
            @ToolParam(required = false, description = "Optional remaining open loops.") List<String> openLoops,
            @ToolParam(description = "Required next action for the receiving agent.") String nextAction) {
        return taskService.completeTask(new CompleteTaskRequest(
                requireUuid(taskId, "Task id"),
                actor,
                source,
                clientSessionId,
                summary,
                openLoops,
                nextAction));
    }

    @Tool(
            description = "List task snapshots with optional project, lane, and status filters and a bounded limit.",
            resultConverter = RestJsonToolCallResultConverter.class)
    public List<TaskSnapshot> listTasks(
            @ToolParam(required = false,
                    description = "Optional exact project key; blank means all projects.") String projectKey,
            @ToolParam(required = false,
                    description = "Optional exact lane; blank means all lanes.") String lane,
            @ToolParam(required = false,
                    description = "Optional status: open, in_progress, blocked, done, or cancelled.") String status,
            @ToolParam(required = false,
                    description = "Maximum snapshots to return, defaulting to 100 and clamped to 1 through 250.")
                    Integer limit) {
        return taskService.listTasks(new TaskQuery(
                        optionalFilter(projectKey),
                        optionalFilter(lane),
                        optionalStatus(status)))
                .stream()
                .limit(safeTaskLimit(limit))
                .toList();
    }

    @Tool(
            description = "Get a durable spec, including its full frozen body and optional provenance.",
            resultConverter = RestJsonToolCallResultConverter.class)
    public TaskSpec getSpec(
            @ToolParam(description = "UUID of the spec to retrieve.") String specId) {
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

    private static ToolCallback withStructuredTaskErrors(ToolCallback delegate, ObjectMapper objectMapper) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return delegate.getToolDefinition();
            }

            @Override
            public ToolMetadata getToolMetadata() {
                return delegate.getToolMetadata();
            }

            @Override
            public String call(String toolInput) {
                try {
                    return delegate.call(toolInput);
                }
                catch (RuntimeException ex) {
                    throw structuredTaskError(delegate, objectMapper, ex);
                }
            }

            @Override
            public String call(String toolInput, ToolContext toolContext) {
                try {
                    return delegate.call(toolInput, toolContext);
                }
                catch (RuntimeException ex) {
                    throw structuredTaskError(delegate, objectMapper, ex);
                }
            }
        };
    }

    private static RuntimeException structuredTaskError(
            ToolCallback delegate,
            ObjectMapper objectMapper,
            RuntimeException failure) {
        TaskDomainException taskError = findTaskError(failure);
        if (taskError == null) {
            return failure;
        }
        String payload;
        try {
            payload = objectMapper.writeValueAsString(new TaskToolErrorEnvelope(new TaskToolError(
                    taskError.code().name().toLowerCase(java.util.Locale.ROOT),
                    taskError.code().name(),
                    taskError.getMessage(),
                    taskError.taskId(),
                    taskError.currentStatus() == null ? null : taskError.currentStatus().value(),
                    taskError.targetStatus() == null ? null : taskError.targetStatus().value())));
        }
        catch (JsonProcessingException ex) {
            return new IllegalStateException("Unable to serialize task tool error", ex);
        }
        return new ToolExecutionException(
                delegate.getToolDefinition(),
                new StructuredTaskToolException(payload, failure));
    }

    private static TaskDomainException findTaskError(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof TaskDomainException taskError) {
                return taskError;
            }
            current = current.getCause();
        }
        return null;
    }

    private record TaskToolErrorEnvelope(TaskToolError error) {
    }

    private record TaskToolError(
            String type,
            String code,
            String message,
            String taskId,
            String currentStatus,
            String targetStatus) {
    }

    private static final class StructuredTaskToolException extends RuntimeException {

        private StructuredTaskToolException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
