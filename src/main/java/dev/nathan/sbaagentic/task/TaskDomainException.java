package dev.nathan.sbaagentic.task;

public class TaskDomainException extends RuntimeException {

    private final TaskErrorCode code;
    private final String taskId;
    private final TaskStatus currentStatus;
    private final TaskStatus targetStatus;

    public TaskDomainException(
            TaskErrorCode code,
            String message,
            String taskId,
            TaskStatus currentStatus,
            TaskStatus targetStatus) {
        super(message);
        this.code = code;
        this.taskId = taskId;
        this.currentStatus = currentStatus;
        this.targetStatus = targetStatus;
    }

    public TaskDomainException(
            TaskErrorCode code,
            String message,
            String taskId,
            TaskStatus currentStatus,
            TaskStatus targetStatus,
            Throwable cause) {
        super(message, cause);
        this.code = code;
        this.taskId = taskId;
        this.currentStatus = currentStatus;
        this.targetStatus = targetStatus;
    }

    public TaskErrorCode code() {
        return code;
    }

    public String taskId() {
        return taskId;
    }

    public TaskStatus currentStatus() {
        return currentStatus;
    }

    public TaskStatus targetStatus() {
        return targetStatus;
    }
}
