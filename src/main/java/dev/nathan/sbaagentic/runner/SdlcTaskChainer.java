package dev.nathan.sbaagentic.runner;

import dev.nathan.sbaagentic.runner.internal.client.blackbox.BlackBoxApiClient;

import java.util.List;
import java.util.Objects;

import dev.nathan.sbaagentic.runner.internal.client.blackbox.Task;
import dev.nathan.sbaagentic.runner.internal.client.blackbox.TaskSnapshot;
import dev.nathan.sbaagentic.runner.internal.client.blackbox.TaskStatus;

import org.springframework.stereotype.Component;

@Component
public class SdlcTaskChainer {

    private final BlackBoxApiClient apiClient;

    public SdlcTaskChainer(BlackBoxApiClient apiClient) {
        this.apiClient = apiClient;
    }

    public synchronized boolean ensureTask(
            String specId,
            String title,
            String lane,
            int priority,
            String actorId) {
        boolean exists = safeList(apiClient.listTasks(null, lane)).stream()
                .filter(Objects::nonNull)
                .map(TaskSnapshot::task)
                .filter(Objects::nonNull)
                .filter(task -> specId.equals(task.specId()))
                .filter(task -> lane.equals(task.lane()))
                .anyMatch(SdlcTaskChainer::isActedOnSuccessor);
        if (exists) {
            return false;
        }
        apiClient.enqueueTask(specId, title, lane, priority, actorId);
        return true;
    }

    private static boolean isActedOnSuccessor(Task task) {
        return task.status() == TaskStatus.OPEN
                || task.status() == TaskStatus.IN_PROGRESS
                || task.status() == TaskStatus.DONE;
    }

    private static <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }
}
