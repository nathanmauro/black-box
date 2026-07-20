package dev.nathan.sbaagentic.dag;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import dev.nathan.sbaagentic.event.EventRepository;
import dev.nathan.sbaagentic.link.SessionLink;
import dev.nathan.sbaagentic.link.SessionLinkRepository;
import dev.nathan.sbaagentic.session.AgentSession;
import dev.nathan.sbaagentic.workflow.Task;
import dev.nathan.sbaagentic.workflow.TaskEvent;
import dev.nathan.sbaagentic.workflow.TaskEventType;
import dev.nathan.sbaagentic.workflow.TaskSnapshot;
import dev.nathan.sbaagentic.workflow.TaskSpec;
import dev.nathan.sbaagentic.workflow.WorkflowOperations;

import org.springframework.stereotype.Service;

@Service
public class DagService {

    private final WorkflowOperations workflow;
    private final SessionLinkRepository sessionLinkRepository;
    private final EventRepository eventRepository;

    public DagService(
            WorkflowOperations workflow,
            SessionLinkRepository sessionLinkRepository,
            EventRepository eventRepository) {
        this.workflow = workflow;
        this.sessionLinkRepository = sessionLinkRepository;
        this.eventRepository = eventRepository;
    }

    public DagResponse forTask(String taskId) {
        requireText(taskId, "Task id");
        TaskSnapshot snapshot = workflow.getTask(taskId);
        return buildForSpecs(List.of(snapshot.spec()));
    }

    public DagResponse forSession(String sessionId) {
        requireText(sessionId, "Session id");
        List<TaskEvent> noteEvents = workflow.eventsByType(TaskEventType.NOTE);
        LinkedHashSet<String> matchingTaskIds = new LinkedHashSet<>();
        for (TaskEvent event : noteEvents) {
            workerSessionId(event)
                    .filter(sessionId::equals)
                    .ifPresent(ignored -> matchingTaskIds.add(event.taskId()));
        }
        if (matchingTaskIds.isEmpty()) {
            return sessionOnlyGraph(sessionId);
        }

        LinkedHashMap<String, TaskSpec> specsById = new LinkedHashMap<>();
        for (String taskId : matchingTaskIds) {
            TaskSpec spec = workflow.getTask(taskId).spec();
            specsById.putIfAbsent(spec.id(), spec);
        }
        return buildForSpecs(List.copyOf(specsById.values()), noteEvents);
    }

    private DagResponse buildForSpecs(List<TaskSpec> specs) {
        return buildForSpecs(specs, workflow.eventsByType(TaskEventType.NOTE));
    }

    private DagResponse buildForSpecs(List<TaskSpec> specs, List<TaskEvent> noteEvents) {
        LinkedHashMap<String, DagNode> nodes = new LinkedHashMap<>();
        LinkedHashSet<DagEdge> edges = new LinkedHashSet<>();
        Map<String, List<String>> sessionsByTask = new LinkedHashMap<>();
        for (TaskEvent event : noteEvents) {
            workerSessionId(event).ifPresent(sessionId -> sessionsByTask
                    .computeIfAbsent(event.taskId(), ignored -> new ArrayList<>())
                    .add(sessionId));
        }

        LinkedHashSet<String> discovered = new LinkedHashSet<>();
        for (TaskSpec spec : specs) {
            String specNodeId = specNodeId(spec.id());
            nodes.putIfAbsent(specNodeId, new DagNode(
                    specNodeId, "spec", spec.title(), spec.status().value(), spec.id()));
            for (Task task : workflow.tasksForSpec(spec.id())) {
                String taskNodeId = taskNodeId(task.id());
                nodes.putIfAbsent(taskNodeId, new DagNode(
                        taskNodeId, "task", task.title(), task.status().value(), task.id()));
                edges.add(new DagEdge(specNodeId, taskNodeId, "has_task"));

                for (String sessionId : sessionsByTask.getOrDefault(task.id(), List.of())) {
                    discovered.add(sessionId);
                    String sessionNodeId = sessionNodeId(sessionId);
                    nodes.putIfAbsent(sessionNodeId, hydrateSessionNode(sessionId));
                    edges.add(new DagEdge(taskNodeId, sessionNodeId, "worker_session"));
                }

                for (SessionLink link : sessionLinkRepository.linksForTaskId(task.id())) {
                    discovered.add(link.parentSessionId());
                    discovered.add(link.childSessionId());
                    String parentSessionNodeId = sessionNodeId(link.parentSessionId());
                    String childSessionNodeId = sessionNodeId(link.childSessionId());
                    nodes.putIfAbsent(parentSessionNodeId, hydrateSessionNode(link.parentSessionId()));
                    nodes.putIfAbsent(childSessionNodeId, hydrateSessionNode(link.childSessionId()));
                    edges.add(new DagEdge(
                            parentSessionNodeId, childSessionNodeId, link.linkType().value()));
                }
            }
        }

        for (String sessionId : List.copyOf(discovered)) {
            for (SessionLink link : sessionLinkRepository.linksWhereParent(sessionId)) {
                String childSessionNodeId = sessionNodeId(link.childSessionId());
                nodes.putIfAbsent(childSessionNodeId, hydrateSessionNode(link.childSessionId()));
                edges.add(new DagEdge(
                        sessionNodeId(sessionId), childSessionNodeId, link.linkType().value()));
            }
            for (SessionLink link : sessionLinkRepository.linksWhereChild(sessionId)) {
                String parentSessionNodeId = sessionNodeId(link.parentSessionId());
                nodes.putIfAbsent(parentSessionNodeId, hydrateSessionNode(link.parentSessionId()));
                edges.add(new DagEdge(
                        parentSessionNodeId, sessionNodeId(sessionId), link.linkType().value()));
            }
        }
        return new DagResponse(List.copyOf(nodes.values()), List.copyOf(edges));
    }

    private DagResponse sessionOnlyGraph(String sessionId) {
        LinkedHashMap<String, DagNode> nodes = new LinkedHashMap<>();
        LinkedHashSet<DagEdge> edges = new LinkedHashSet<>();
        String sessionNodeId = sessionNodeId(sessionId);
        nodes.put(sessionNodeId, hydrateSessionNode(sessionId));
        for (SessionLink link : sessionLinkRepository.linksWhereParent(sessionId)) {
            String childSessionNodeId = sessionNodeId(link.childSessionId());
            nodes.putIfAbsent(childSessionNodeId, hydrateSessionNode(link.childSessionId()));
            edges.add(new DagEdge(sessionNodeId, childSessionNodeId, link.linkType().value()));
        }
        for (SessionLink link : sessionLinkRepository.linksWhereChild(sessionId)) {
            String parentSessionNodeId = sessionNodeId(link.parentSessionId());
            nodes.putIfAbsent(parentSessionNodeId, hydrateSessionNode(link.parentSessionId()));
            edges.add(new DagEdge(parentSessionNodeId, sessionNodeId, link.linkType().value()));
        }
        return new DagResponse(List.copyOf(nodes.values()), List.copyOf(edges));
    }

    private static String specNodeId(String specId) {
        return "spec:" + specId;
    }

    private static String taskNodeId(String taskId) {
        return "task:" + taskId;
    }

    private static String sessionNodeId(String sessionId) {
        return "session:" + sessionId;
    }

    private DagNode hydrateSessionNode(String sessionId) {
        String label = eventRepository.findSessionById(sessionId)
                .map(AgentSession::title)
                .orElse(sessionId);
        return new DagNode(sessionNodeId(sessionId), "session", label, null, sessionId);
    }

    /**
     * Extracts the {@code sessionId} carried by a {@code worker_session} annotation event, or empty
     * if this event isn't a worker_session annotation or its payload doesn't parse.
     */
    private static Optional<String> workerSessionId(TaskEvent event) {
        if (event.type() != TaskEventType.NOTE || event.detail() == null) {
            return Optional.empty();
        }
        if (!"worker_session".equals(event.detail().get("kind"))) {
            return Optional.empty();
        }
        Object dataJson = event.detail().get("dataJson");
        if (!(dataJson instanceof Map<?, ?> data)) {
            return Optional.empty();
        }
        Object sessionId = data.get("sessionId");
        return (sessionId instanceof String value && !value.isBlank())
                ? Optional.of(value)
                : Optional.empty();
    }

    private static void requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
    }
}
