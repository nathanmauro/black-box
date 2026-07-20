package dev.nathan.sbaagentic.workflow.internal.application.port;

import java.util.Map;
import java.util.Optional;

import dev.nathan.sbaagentic.workflow.TaskSpec;

public interface SpecStore {

    TaskSpec createSpec(String projectKey, String title, String body, Map<String, Object> specRef, String createdBy);

    Optional<TaskSpec> findSpec(String specId);
}
