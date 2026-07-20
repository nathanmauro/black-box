package dev.nathan.sbaagentic.workflow.internal.application.port;

import java.util.List;
import java.util.Optional;

import dev.nathan.sbaagentic.workflow.TaskChange;
import dev.nathan.sbaagentic.workflow.TaskQuery;
import dev.nathan.sbaagentic.workflow.TaskSnapshot;
import dev.nathan.sbaagentic.workflow.internal.domain.TaskUpdate;

public interface TaskLifecycleStore {

    TaskChange enqueueTask(String specId, String title, String lane, int priority, String createdBy);

    Optional<TaskSnapshot> findTask(String taskId);

    List<TaskSnapshot> listTasks(TaskQuery query);

    Optional<TaskChange> claimNextTask(String lane, String agent);

    Optional<TaskChange> updateTask(TaskUpdate update);
}
