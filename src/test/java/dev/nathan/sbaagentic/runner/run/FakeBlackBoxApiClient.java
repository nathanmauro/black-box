package dev.nathan.sbaagentic.runner.run;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nathan.sbaagentic.event.IngestResponse;
import dev.nathan.sbaagentic.link.SessionLink;
import dev.nathan.sbaagentic.runner.BlackBoxApiClient;
import dev.nathan.sbaagentic.task.TaskAnnotation;
import dev.nathan.sbaagentic.task.TaskChange;
import dev.nathan.sbaagentic.task.TaskEvent;

final class FakeBlackBoxApiClient extends BlackBoxApiClient {

    List<TaskEvent> taskEvents = List.of();
    java.util.function.Supplier<List<TaskEvent>> taskEventsSupplier = () -> taskEvents;
    IngestResponse ingestResponse = new IngestResponse(
            "event-1", "session-1", "codex", "client-1", "session_meta", false);
    final List<PostEventCall> postEventCalls = new ArrayList<>();
    final List<AnnotationCall> annotationCalls = new ArrayList<>();
    final List<SessionLinkCall> sessionLinkCalls = new ArrayList<>();
    final List<CompleteCall> completeCalls = new ArrayList<>();
    final List<StatusCall> statusCalls = new ArrayList<>();

    FakeBlackBoxApiClient() {
        super(new ObjectMapper());
    }

    @Override
    public List<TaskEvent> taskEvents(String taskId) {
        return taskEventsSupplier.get();
    }

    @Override
    public IngestResponse postEvent(
            String source,
            String clientSessionId,
            String turnId,
            String eventType,
            String role,
            String text,
            String cwd,
            String toolName,
            Object toolInput,
            Object toolOutput,
            Map<String, Object> metadata,
            Instant observedAt) {
        postEventCalls.add(new PostEventCall(
                source,
                clientSessionId,
                turnId,
                eventType,
                role,
                text,
                cwd,
                toolName,
                toolInput,
                toolOutput,
                metadata,
                observedAt));
        return ingestResponse;
    }

    @Override
    public SessionLink createSessionLink(
            String parentSessionId, String childSessionId, String linkType, String taskId) {
        sessionLinkCalls.add(new SessionLinkCall(
                parentSessionId, childSessionId, linkType, taskId));
        return null;
    }

    @Override
    public TaskAnnotation annotate(
            String taskId,
            String actor,
            String kind,
            String text,
            Map<String, Object> dataJson) {
        annotationCalls.add(new AnnotationCall(taskId, actor, kind, text, dataJson));
        return null;
    }

    @Override
    public TaskChange completeTask(
            String taskId,
            String actor,
            String source,
            String clientSessionId,
            String summary,
            List<String> openLoops,
            String nextAction) {
        completeCalls.add(new CompleteCall(
                taskId, actor, source, clientSessionId, summary, openLoops, nextAction));
        return null;
    }

    @Override
    public TaskChange updateTaskStatus(
            String taskId, String actor, String status, String blockedReason) {
        statusCalls.add(new StatusCall(taskId, actor, status, blockedReason));
        return null;
    }

    record PostEventCall(
            String source,
            String clientSessionId,
            String turnId,
            String eventType,
            String role,
            String text,
            String cwd,
            String toolName,
            Object toolInput,
            Object toolOutput,
            Map<String, Object> metadata,
            Instant observedAt) {
    }

    record AnnotationCall(
            String taskId,
            String actor,
            String kind,
            String text,
            Map<String, Object> dataJson) {
    }

    record SessionLinkCall(
            String parentSessionId, String childSessionId, String linkType, String taskId) {
    }

    record CompleteCall(
            String taskId,
            String actor,
            String source,
            String clientSessionId,
            String summary,
            List<String> openLoops,
            String nextAction) {
    }

    record StatusCall(String taskId, String actor, String status, String blockedReason) {
    }
}
