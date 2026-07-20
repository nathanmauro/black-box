package dev.nathan.sbaagentic.workflow.internal.adapter.in.web;

import java.util.UUID;

import dev.nathan.sbaagentic.workflow.DagOperations;
import dev.nathan.sbaagentic.workflow.DagResponse;
import dev.nathan.sbaagentic.workflow.TaskDomainException;
import dev.nathan.sbaagentic.workflow.TaskErrorCode;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class DagController {

    private final DagOperations dags;

    public DagController(DagOperations dags) {
        this.dags = dags;
    }

    @GetMapping("/tasks/{taskId}/dag")
    public DagResponse taskDag(@PathVariable String taskId) {
        return dags.forTask(requireUuid(taskId));
    }

    @GetMapping("/dag")
    public DagResponse dag(@RequestParam String sessionId) {
        return dags.forSession(sessionId);
    }

    private static String requireUuid(String value) {
        if (value == null || value.isBlank()) {
            throw validation("Task id is required");
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
            throw validation("Task id must be a UUID");
        }
    }

    private static TaskDomainException validation(String message) {
        return new TaskDomainException(TaskErrorCode.VALIDATION_FAILED, message, null, null, null);
    }
}
