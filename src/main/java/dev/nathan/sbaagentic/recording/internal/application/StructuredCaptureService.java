package dev.nathan.sbaagentic.recording.internal.application;

import dev.nathan.sbaagentic.recording.CaptureDecisionRequest;
import dev.nathan.sbaagentic.recording.CaptureHandoffRequest;
import dev.nathan.sbaagentic.recording.CaptureProjectionRequest;
import dev.nathan.sbaagentic.recording.EventIngestRequest;
import dev.nathan.sbaagentic.recording.EventRecorder;
import dev.nathan.sbaagentic.recording.IngestResponse;
import dev.nathan.sbaagentic.recording.ProjectionPath;
import dev.nathan.sbaagentic.recording.RecordingCaptureOperations;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class StructuredCaptureService implements RecordingCaptureOperations {

    private static final String KIND_DECISION = "decision";
    private static final String KIND_HANDOFF = "handoff";
    private static final String KIND_OBSERVATION = "observation";
    private static final String KIND_PROJECTION = "projection";
    private static final int MAX_PROJECTION_PATHS = 5;

    private final EventRecorder recorder;

    public StructuredCaptureService(EventRecorder recorder) {
        this.recorder = recorder;
    }

    @Override
    public IngestResponse captureDecision(CaptureDecisionRequest request) {
        requireNotBlank("decision", request.decision());
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("kind", KIND_DECISION);
        metadata.put("decision", request.decision());
        putIfPresent(metadata, "rationale", request.rationale());
        putIfPresent(metadata, "alternatives", trimList(request.alternatives()));
        putIfPresent(metadata, "openLoops", trimList(request.openLoops()));
        putIfPresent(metadata, "confidence", request.confidence());
        putIfPresent(metadata, "repo", request.repo());

        return write(
                request.source(),
                request.clientSessionId(),
                request.repo(),
                "Decision",
                renderDecision(request),
                metadata);
    }

    @Override
    public IngestResponse captureHandoff(CaptureHandoffRequest request) {
        requireNotBlank("contextSummary", request.contextSummary());
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("kind", KIND_HANDOFF);
        metadata.put("contextSummary", request.contextSummary());
        putIfPresent(metadata, "toAgent", request.toAgent());
        putIfPresent(metadata, "openLoops", trimList(request.openLoops()));
        putIfPresent(metadata, "nextAction", request.nextAction());
        putIfPresent(metadata, "repo", request.repo());

        return write(
                request.source(),
                request.clientSessionId(),
                request.repo(),
                "Handoff",
                renderHandoff(request),
                metadata);
    }

    @Override
    public IngestResponse captureProjection(CaptureProjectionRequest request) {
        List<ProjectionPath> paths = trimPaths(request.paths());
        if (paths.isEmpty()) {
            throw new IllegalArgumentException("Projection paths must include at least one path with a title.");
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("kind", KIND_PROJECTION);
        metadata.put("paths", pathMetadata(paths));
        putIfPresent(metadata, "basis", request.basis());
        putIfPresent(metadata, "repo", request.repo());

        return write(
                request.source(),
                request.clientSessionId(),
                request.repo(),
                "Projection",
                renderProjection(request, paths),
                metadata);
    }

    @Override
    public IngestResponse captureObservation(String source, String clientSessionId, String repo, String text) {
        requireNotBlank("text", text);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("kind", KIND_OBSERVATION);
        putIfPresent(metadata, "repo", repo);

        return write(source, clientSessionId, repo, "Observation", text, metadata);
    }

    private IngestResponse write(
            String source,
            String clientSessionId,
            String repo,
            String eventType,
            String text,
            Map<String, Object> metadata) {
        // MCP and in-process callers do not pass through REST's @Valid request validation.
        requireNotBlank("source", source);
        requireNotBlank("clientSessionId", clientSessionId);

        return recorder.ingest(new EventIngestRequest(
                source,
                clientSessionId,
                null,
                eventType,
                "assistant",
                text,
                repo,
                null,
                null,
                null,
                metadata,
                Instant.now()));
    }

    private static String renderDecision(CaptureDecisionRequest request) {
        StringBuilder body = new StringBuilder(request.decision().strip());
        appendBlock(body, "Why", request.rationale());
        appendList(body, "Considered", request.alternatives());
        appendList(body, "Open loops", request.openLoops());
        if (request.confidence() != null) {
            body.append("\n\nConfidence: ").append(request.confidence());
        }

        return body.toString();
    }

    private static String renderHandoff(CaptureHandoffRequest request) {
        StringBuilder body = new StringBuilder();
        if (notBlank(request.toAgent())) {
            body.append("Handoff to ").append(request.toAgent().strip()).append(": ");
        }
        body.append(request.contextSummary().strip());
        appendList(body, "Open loops", request.openLoops());
        appendBlock(body, "Next", request.nextAction());

        return body.toString();
    }

    private static String renderProjection(CaptureProjectionRequest request, List<ProjectionPath> paths) {
        StringBuilder body = new StringBuilder("Projected futures:");
        for (int i = 0; i < paths.size(); i++) {
            appendPath(body, i + 1, paths.get(i));
        }
        appendBlock(body, "Basis", request.basis());

        return body.toString();
    }

    private static void appendPath(StringBuilder body, int index, ProjectionPath path) {
        body.append("\n").append(index).append(". ");
        if (notBlank(path.title())) {
            body.append(path.title());
        }
        if (notBlank(path.description())) {
            if (notBlank(path.title())) {
                body.append(" — ");
            }
            body.append(path.description());
        }
        if (path.confidence() != null) {
            body.append(" (confidence: ").append(path.confidence()).append(")");
        }
    }

    private static void appendBlock(StringBuilder body, String label, String value) {
        if (notBlank(value)) {
            body.append("\n\n").append(label).append(": ").append(value.strip());
        }
    }

    private static void appendList(StringBuilder body, String label, List<String> values) {
        List<String> trimmed = trimList(values);
        if (trimmed != null) {
            body.append("\n\n").append(label).append(": ").append(String.join("; ", trimmed));
        }
    }

    private static List<String> trimList(List<String> values) {
        if (values == null) {

            return null;
        }
        List<String> out = new ArrayList<>();
        for (String value : values) {
            if (notBlank(value)) {
                out.add(value.strip());
            }
        }

        return out.isEmpty() ? null : out;
    }

    private static List<ProjectionPath> trimPaths(List<ProjectionPath> paths) {
        if (paths == null) {

            return List.of();
        }
        List<ProjectionPath> out = new ArrayList<>();
        for (ProjectionPath path : paths) {
            if (path == null) {
                continue;
            }
            String title = stripOrNull(path.title());
            String description = stripOrNull(path.description());
            if (title != null) {
                out.add(new ProjectionPath(title, description, path.confidence()));
            }
            if (out.size() == MAX_PROJECTION_PATHS) {
                break;
            }
        }

        return out;
    }

    private static List<Map<String, Object>> pathMetadata(List<ProjectionPath> paths) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ProjectionPath path : paths) {
            Map<String, Object> item = new LinkedHashMap<>();
            putIfPresent(item, "title", path.title());
            putIfPresent(item, "description", path.description());
            putIfPresent(item, "confidence", path.confidence());
            out.add(item);
        }

        return out;
    }

    private static void putIfPresent(Map<String, Object> metadata, String key, Object value) {
        if (value != null) {
            metadata.put(key, value);
        }
    }

    private static String stripOrNull(String value) {

        return notBlank(value) ? value.strip() : null;
    }

    private static boolean notBlank(String value) {

        return value != null && !value.isBlank();
    }

    private static void requireNotBlank(String field, String value) {
        if (!notBlank(value)) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
