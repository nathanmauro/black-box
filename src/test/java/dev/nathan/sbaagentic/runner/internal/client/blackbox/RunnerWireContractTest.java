package dev.nathan.sbaagentic.runner.internal.client.blackbox;

import java.time.Instant;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RunnerWireContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void taskChangeJsonMatchesTheServerContract() throws Exception {
        Instant observedAt = Instant.parse("2026-07-20T12:34:56Z");
        var serverTask = new dev.nathan.sbaagentic.workflow.Task(
                "task-1", "spec-1", "/repos/example", "Implement contract", "codex",
                dev.nathan.sbaagentic.workflow.TaskStatus.IN_PROGRESS, 7, "planner", "worker",
                null, null, observedAt.minusSeconds(60), observedAt);
        var serverSpec = new dev.nathan.sbaagentic.workflow.TaskSpec(
                "spec-1", "/repos/example", "Contract", "Frozen body", Map.of("sha", "abc123"),
                dev.nathan.sbaagentic.workflow.SpecStatus.ACTIVE, "planner",
                observedAt.minusSeconds(120), observedAt.minusSeconds(120));
        var serverEvent = new dev.nathan.sbaagentic.workflow.TaskEvent(
                "event-1", "task-1", dev.nathan.sbaagentic.workflow.TaskEventType.CLAIMED,
                "worker", dev.nathan.sbaagentic.workflow.TaskStatus.OPEN,
                dev.nathan.sbaagentic.workflow.TaskStatus.IN_PROGRESS, Map.of("attempt", 1), observedAt);
        var serverChange = new dev.nathan.sbaagentic.workflow.TaskChange(
                new dev.nathan.sbaagentic.workflow.TaskSnapshot(serverTask, serverSpec), serverEvent);

        JsonNode serverJson = objectMapper.valueToTree(serverChange);
        TaskChange runnerWire = objectMapper.treeToValue(serverJson, TaskChange.class);
        JsonNode runnerJson = objectMapper.valueToTree(runnerWire);

        assertThat(runnerJson).isEqualTo(serverJson);
    }

    @Test
    void ingestResponseJsonMatchesTheServerContract() throws Exception {
        var serverResponse = new dev.nathan.sbaagentic.recording.IngestResponse(
                "event-1", "session-1", "codex", "client-1", "Handoff", true);

        JsonNode serverJson = objectMapper.valueToTree(serverResponse);
        IngestResponse runnerWire = objectMapper.treeToValue(serverJson, IngestResponse.class);
        JsonNode runnerJson = objectMapper.valueToTree(runnerWire);

        assertThat(runnerJson).isEqualTo(serverJson);
    }
}
