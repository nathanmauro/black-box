package dev.nathan.sbaagentic.task;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import dev.nathan.sbaagentic.context.CaptureHandoffRequest;
import dev.nathan.sbaagentic.context.ContextService;
import dev.nathan.sbaagentic.event.IngestResponse;
import dev.nathan.sbaagentic.stream.EventBroadcaster;
import dev.nathan.sbaagentic.stream.StreamEvents;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class TaskService {

    private static final Logger log = LoggerFactory.getLogger(TaskService.class);

    private final TaskRepository repository;
    private final ContextService contextService;
    private final EventBroadcaster broadcaster;
    private final TransactionTemplate transactionTemplate;

    public TaskService(
            TaskRepository repository,
            ContextService contextService,
            EventBroadcaster broadcaster,
            PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.contextService = contextService;
        this.broadcaster = broadcaster;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public TaskSpec createSpec(CreateSpecRequest request) {
        requireRequest(request, "Spec request");
        requireText(request.projectKey(), "Project key");
        requireText(request.title(), "Spec title");
        requireText(request.body(), "Spec body");
        requireText(request.actor(), "Spec actor");
        return repository.createSpec(
                request.projectKey(), request.title(), request.body(), request.specRef(), request.actor());
    }

    public TaskChange enqueueTask(EnqueueTaskRequest request) {
        requireRequest(request, "Enqueue request");
        requireText(request.specId(), "Spec id");
        requireText(request.title(), "Task title");
        requireText(request.lane(), "Task lane");
        requireText(request.actor(), "Task actor");
        if (repository.findSpec(request.specId()).isEmpty()) {
            throw error(TaskErrorCode.SPEC_NOT_FOUND, "Spec not found: " + request.specId(), null, null, null);
        }
        TaskChange change = repository.enqueueTask(
                request.specId(), request.title(), request.lane(), request.priority(), request.actor());
        publishCommitted(change);
        return change;
    }

    public Optional<TaskChange> claimNextTask(ClaimTaskRequest request) {
        requireRequest(request, "Claim request");
        requireText(request.lane(), "Task lane");
        requireText(request.agent(), "Claiming agent");
        Optional<TaskChange> change = repository.claimNextTask(request.lane(), request.agent());
        change.ifPresent(this::publishCommitted);
        return change;
    }

    public List<TaskSnapshot> listTasks(TaskQuery query) {
        return repository.listTasks(query);
    }

    public TaskSpec getSpec(String specId) {
        requireText(specId, "Spec id");
        return repository.findSpec(specId)
                .orElseThrow(() -> error(
                        TaskErrorCode.SPEC_NOT_FOUND, "Spec not found: " + specId, null, null, null));
    }

    public TaskSnapshot getTask(String taskId) {
        requireText(taskId, "Task id");
        return repository.findTask(taskId)
                .orElseThrow(() -> error(
                        TaskErrorCode.TASK_NOT_FOUND, "Task not found: " + taskId, taskId, null, null));
    }

    public TaskAnnotation createAnnotation(CreateAnnotationRequest request) {
        requireRequest(request, "Annotation request");
        requireText(request.taskId(), "Task id");
        requireText(request.actor(), "Annotation actor");
        requireText(request.kind(), "Annotation kind");
        requireText(request.text(), "Annotation text");

        AnnotationKind kind;
        try {
            kind = AnnotationKind.fromValue(request.kind());
        }
        catch (IllegalArgumentException ex) {
            throw validation("Unknown annotation kind: " + request.kind(), request.taskId());
        }
        Task task = getTask(request.taskId()).task();
        TaskAnnotation annotation = repository.appendAnnotation(
                request.taskId(), kind, request.actor(), request.text(), request.dataJson());
        try {
            broadcaster.publishTaskNote(new StreamEvents.TaskNoted(
                    task, annotation, annotation.observedAt().toString()));
        }
        catch (RuntimeException ex) {
            log.warn(
                    "Task annotation committed but SSE publish failed for task {} kind {}",
                    request.taskId(),
                    kind,
                    ex);
        }
        return annotation;
    }

    public List<TaskEvent> getTaskEvents(String taskId) {
        requireText(taskId, "Task id");
        getTask(taskId);
        return repository.eventsForTask(taskId);
    }

    public TaskChange updateTaskStatus(UpdateTaskStatusRequest request) {
        requireRequest(request, "Task update request");
        requireText(request.taskId(), "Task id");
        requireText(request.actor(), "Task actor");
        if (request.status() == null) {
            throw validation("Target task status is required", request.taskId());
        }

        Task current = getTask(request.taskId()).task();
        TaskUpdate update = transitionUpdate(current, request);
        TaskChange change = repository.updateTask(update)
                .orElseThrow(() -> error(
                        TaskErrorCode.CONCURRENT_MODIFICATION,
                        "Task changed before the transition could be committed: " + current.id(),
                        current.id(),
                        current.status(),
                        request.status()));
        publishCommitted(change);
        return change;
    }

    public TaskChange completeTask(CompleteTaskRequest request) {
        requireRequest(request, "Task completion request");
        requireText(request.taskId(), "Task id");
        requireText(request.actor(), "Task actor");
        requireText(request.source(), "Handoff source");
        requireText(request.clientSessionId(), "Handoff client session id");
        requireText(request.summary(), "Completion summary");
        requireText(request.nextAction(), "Completion next action");

        TaskChange change = Objects.requireNonNull(transactionTemplate.execute(status -> completeInTransaction(request)));
        publishCommitted(change);
        return change;
    }

    private TaskChange completeInTransaction(CompleteTaskRequest request) {
        Task current = getTask(request.taskId()).task();
        if (current.status() != TaskStatus.IN_PROGRESS) {
            throw invalidTransition(current, TaskStatus.DONE);
        }
        if (current.claimedBy() == null || !current.claimedBy().equals(request.actor())) {
            throw error(
                    TaskErrorCode.CLAIMANT_MISMATCH,
                    "Only the current claimant may complete task " + current.id(),
                    current.id(),
                    current.status(),
                    TaskStatus.DONE);
        }

        IngestResponse handoff;
        try {
            handoff = contextService.captureHandoff(new CaptureHandoffRequest(
                    request.source(),
                    request.clientSessionId(),
                    current.projectKey(),
                    null,
                    request.summary(),
                    request.openLoops(),
                    request.nextAction()));
        }
        catch (RuntimeException ex) {
            throw new TaskDomainException(
                    TaskErrorCode.HANDOFF_FAILED,
                    "Unable to capture completion Handoff for task " + current.id(),
                    current.id(),
                    current.status(),
                    TaskStatus.DONE,
                    ex);
        }

        return repository.updateTask(new TaskUpdate(
                        current.id(),
                        TaskStatus.IN_PROGRESS,
                        TaskStatus.DONE,
                        request.actor(),
                        TaskEventType.COMPLETED,
                        current.claimedBy(),
                        null,
                        handoff.eventId(),
                        Map.of("handoffId", handoff.eventId())))
                .orElseThrow(() -> error(
                        TaskErrorCode.CONCURRENT_MODIFICATION,
                        "Task changed before completion could be committed: " + current.id(),
                        current.id(),
                        current.status(),
                        TaskStatus.DONE));
    }

    private TaskUpdate transitionUpdate(Task current, UpdateTaskStatusRequest request) {
        return switch (request.status()) {
            case BLOCKED -> {
                if (current.status() != TaskStatus.IN_PROGRESS) {
                    throw invalidTransition(current, TaskStatus.BLOCKED);
                }
                requireText(request.blockedReason(), "Blocked reason");
                String reason = request.blockedReason().strip();
                yield new TaskUpdate(
                        current.id(),
                        current.status(),
                        TaskStatus.BLOCKED,
                        request.actor(),
                        TaskEventType.BLOCKED,
                        current.claimedBy(),
                        reason,
                        current.resultHandoffId(),
                        Map.of("reason", reason));
            }
            case OPEN -> {
                if (current.status() != TaskStatus.IN_PROGRESS && current.status() != TaskStatus.BLOCKED) {
                    throw invalidTransition(current, TaskStatus.OPEN);
                }
                yield new TaskUpdate(
                        current.id(),
                        current.status(),
                        TaskStatus.OPEN,
                        request.actor(),
                        TaskEventType.RESET,
                        null,
                        null,
                        current.resultHandoffId(),
                        null);
            }
            case CANCELLED -> {
                if (current.status() != TaskStatus.OPEN
                        && current.status() != TaskStatus.IN_PROGRESS
                        && current.status() != TaskStatus.BLOCKED) {
                    throw invalidTransition(current, TaskStatus.CANCELLED);
                }
                yield new TaskUpdate(
                        current.id(),
                        current.status(),
                        TaskStatus.CANCELLED,
                        request.actor(),
                        TaskEventType.CANCELLED,
                        current.claimedBy(),
                        current.blockedReason(),
                        current.resultHandoffId(),
                        null);
            }
            default -> throw invalidTransition(current, request.status());
        };
    }

    private void publishCommitted(TaskChange change) {
        TaskEvent event = change.event();
        try {
            broadcaster.publishTaskChanged(new StreamEvents.TaskChanged(
                    change.snapshot().task(),
                    event.id(),
                    event.type().value(),
                    event.observedAt().toString()));
        }
        catch (RuntimeException ex) {
            log.warn(
                    "Task mutation committed but SSE publish failed for task {} transition {}",
                    event.taskId(),
                    event.type().value(),
                    ex);
        }
    }

    private static TaskDomainException invalidTransition(Task current, TaskStatus target) {
        return error(
                TaskErrorCode.INVALID_TRANSITION,
                "Task " + current.id() + " cannot transition from " + current.status().value()
                        + " to " + target.value(),
                current.id(),
                current.status(),
                target);
    }

    private static void requireRequest(Object request, String label) {
        if (request == null) {
            throw validation(label + " is required", null);
        }
    }

    private static void requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw validation(label + " is required", null);
        }
    }

    private static TaskDomainException validation(String message, String taskId) {
        return error(TaskErrorCode.VALIDATION_FAILED, message, taskId, null, null);
    }

    private static TaskDomainException error(
            TaskErrorCode code,
            String message,
            String taskId,
            TaskStatus currentStatus,
            TaskStatus targetStatus) {
        return new TaskDomainException(code, message, taskId, currentStatus, targetStatus);
    }
}
