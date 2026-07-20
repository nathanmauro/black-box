package dev.nathan.sbaagentic.workflow;

import java.util.List;
import java.util.Optional;

/** Synchronous workflow use cases exposed to inbound adapters and dependent projections. */
public interface WorkflowOperations {

    TaskSpec createSpec(CreateSpecRequest request);

    TaskChange enqueueTask(EnqueueTaskRequest request);

    Optional<TaskChange> claimNextTask(ClaimTaskRequest request);

    List<TaskSnapshot> listTasks(TaskQuery query);

    TaskSpec getSpec(String specId);

    TaskSnapshot getTask(String taskId);

    TaskAnnotation createAnnotation(CreateAnnotationRequest request);

    List<TaskEvent> getTaskEvents(String taskId);

    TaskChange updateTaskStatus(UpdateTaskStatusRequest request);

    TaskChange completeTask(CompleteTaskRequest request);

    List<Task> tasksForSpec(String specId);

    List<TaskEvent> eventsByType(TaskEventType type);
}
