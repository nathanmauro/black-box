package dev.nathan.sbaagentic.workflow.internal.application.port;

import java.util.List;
import java.util.Map;

import dev.nathan.sbaagentic.workflow.AnnotationKind;
import dev.nathan.sbaagentic.workflow.Task;
import dev.nathan.sbaagentic.workflow.TaskAnnotation;
import dev.nathan.sbaagentic.workflow.TaskEvent;
import dev.nathan.sbaagentic.workflow.TaskEventType;

public interface TaskHistoryStore {

    TaskAnnotation appendAnnotation(
            String taskId, AnnotationKind kind, String actor, String text, Map<String, Object> dataJson);

    List<TaskEvent> eventsForTask(String taskId);

    List<Task> listTasksBySpec(String specId);

    List<TaskEvent> eventsByType(TaskEventType type);
}
