package dev.nathan.sbaagentic.recording.internal.application;

import dev.nathan.sbaagentic.recording.CaptureDecisionRequest;
import dev.nathan.sbaagentic.recording.CaptureHandoffRequest;
import dev.nathan.sbaagentic.recording.CaptureIdeaRequest;
import dev.nathan.sbaagentic.recording.CaptureProjectionRequest;
import dev.nathan.sbaagentic.recording.EventIngestRequest;
import dev.nathan.sbaagentic.recording.EventRecorder;
import dev.nathan.sbaagentic.recording.Ideas;
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
    private final RedactionService redaction;
    private final dev.nathan.sbaagentic.recording.ProjectScopeResolver projects;

    public StructuredCaptureService(EventRecorder recorder, RedactionService redaction) {
        this(recorder, redaction, scope -> List.of(scope));
    }

    @org.springframework.beans.factory.annotation.Autowired
    public StructuredCaptureService(
            EventRecorder recorder,
            RedactionService redaction,
            dev.nathan.sbaagentic.recording.ProjectScopeResolver projects) {
        this.recorder = recorder;
        this.redaction = redaction;
        this.projects = projects;
    }

    @Override
    public IngestResponse captureDecision(CaptureDecisionRequest request) {
        requireNotBlank("decision", request.decision());
        requireConfidence("confidence", request.confidence());
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("kind", KIND_DECISION);
        metadata.put("decision", request.decision());
        putIfPresent(metadata, "rationale", request.rationale());
        putIfPresent(metadata, "alternatives", trimList(request.alternatives()));
        putIfPresent(metadata, "openLoops", trimList(request.openLoops()));
        putIfPresent(metadata, "confidence", request.confidence());
        putIfPresent(metadata, "repo", request.repo());

        if (request.supersedes() != null) {
            requireNotBlank("supersedes", request.supersedes());
            requireNotBlank("rationale", request.rationale());
            requireNotBlank("repo", request.repo());
            List<String> projectScopes = projects.scopesFor(request.repo());
            if (projectScopes.isEmpty() || projectScopes.contains("__no_project__")) {
                throw new IllegalArgumentException("Decision replacement requires an identifiable project.");
            }
            requireNotBlank("source", request.source());
            requireNotBlank("clientSessionId", request.clientSessionId());

            return recorder.ingestDecisionReplacement(
                    new EventIngestRequest(
                            request.source(),
                            request.clientSessionId(),
                            null,
                            "Decision",
                            "assistant",
                            renderDecision(request),
                            request.repo(),
                            null,
                            null,
                            null,
                            metadata,
                            Instant.now()),
                    request.supersedes().strip(),
                    projectScopes);
        }

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
    public IngestResponse captureIdea(CaptureIdeaRequest request, String migratedFrom, Instant observedAt) {
        if (request == null) {
            throw new IllegalArgumentException("Idea request is required.");
        }
        requireNotBlank("title", request.title());
        requireNotBlank("oneLiner", request.oneLiner());
        if (!notBlank(request.origin())) {
            throw new IllegalArgumentException(
                    "origin must not be blank; allowed values: " + String.join(", ", Ideas.ORIGINS) + ".");
        }
        String origin = Ideas.normalizeOrigin(request.origin());
        if (origin == null) {
            throw new IllegalArgumentException("origin '" + request.origin().strip() + "' is not allowed; use one of: "
                    + String.join(", ", Ideas.ORIGINS) + ".");
        }
        String status = Ideas.STATUS_UNTOUCHED;
        if (notBlank(request.status())) {
            status = Ideas.normalizeStatus(request.status());
            if (status == null) {
                throw new IllegalArgumentException("status '" + request.status().strip()
                        + "' is not allowed; use one of: " + String.join(", ", Ideas.STATUSES) + ".");
            }
        }
        Integer legs = request.legs();
        if (legs != null && (legs < Ideas.MIN_LEGS || legs > Ideas.MAX_LEGS)) {
            throw new IllegalArgumentException(
                    "legs must be between " + Ideas.MIN_LEGS + " and " + Ideas.MAX_LEGS + " (got " + legs + ").");
        }
        String title = request.title().strip();
        String oneLiner = request.oneLiner().strip();
        String repo = stripOrNull(request.repo());
        // Slug the redacted title: slugging lowercases and rewrites the separators the redaction
        // patterns anchor on (AKIA…, ghp_…, "Bearer ", "password:"), so a secret in the raw title
        // would survive ingest-time redaction inside the key.
        String ideaKey = notBlank(request.ideaKey())
                ? request.ideaKey().strip()
                : Ideas.defaultKey(redaction.redact(repo), redaction.redact(title));
        List<String> connects = trimList(request.connects());

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("kind", Ideas.KIND);
        metadata.put("ideaKey", ideaKey);
        metadata.put("title", title);
        metadata.put("oneLiner", oneLiner);
        metadata.put("origin", origin);
        metadata.put("status", status);
        putIfPresent(metadata, "legs", legs);
        putIfPresent(metadata, "quote", stripOrNull(request.quote()));
        putIfPresent(metadata, "sourceRef", stripOrNull(request.sourceRef()));
        putIfPresent(metadata, "connects", connects);
        putIfPresent(metadata, "resumeStep", stripOrNull(request.resumeStep()));
        putIfPresent(metadata, "link", stripOrNull(request.link()));
        putIfPresent(metadata, "notes", stripOrNull(request.notes()));
        putIfPresent(metadata, "repo", repo);
        putIfPresent(metadata, "migratedFrom", stripOrNull(migratedFrom));

        return write(
                request.source(),
                request.clientSessionId(),
                repo,
                Ideas.EVENT_TYPE,
                renderIdea(title, oneLiner, origin, status, legs, connects, request, ideaKey),
                metadata,
                observedAt);
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

        return write(source, clientSessionId, repo, eventType, text, metadata, null);
    }

    private IngestResponse write(
            String source,
            String clientSessionId,
            String repo,
            String eventType,
            String text,
            Map<String, Object> metadata,
            Instant observedAt) {
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
                observedAt == null ? Instant.now() : observedAt));
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

    private static String renderIdea(
            String title,
            String oneLiner,
            String origin,
            String status,
            Integer legs,
            List<String> connects,
            CaptureIdeaRequest request,
            String ideaKey) {
        StringBuilder body = new StringBuilder(Ideas.TEXT_PREFIX)
                .append(' ')
                .append(title)
                .append(" — ")
                .append(oneLiner)
                .append("\n");
        appendLine(body, "Origin", origin);
        appendLine(body, "Status", status);
        if (legs != null) {
            appendLine(body, "Legs", legs + "/" + Ideas.MAX_LEGS);
        }
        if (notBlank(request.quote())) {
            appendLine(body, "Quote", "\"" + request.quote().strip() + "\"");
        }
        appendLine(body, "Source", request.sourceRef());
        if (connects != null) {
            appendLine(body, "Connects", String.join("; ", connects));
        }
        appendLine(body, "Resume", request.resumeStep());
        appendLine(body, "Link", request.link());
        appendLine(body, "Idea key", ideaKey);
        appendBlock(body, "Notes", request.notes());

        return body.toString();
    }

    private static void appendLine(StringBuilder body, String label, String value) {
        if (notBlank(value)) {
            body.append("\n").append(label).append(": ").append(value.strip());
        }
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
        for (int index = 0; index < paths.size(); index++) {
            ProjectionPath path = paths.get(index);
            if (path == null) {
                continue;
            }
            String title = stripOrNull(path.title());
            String description = stripOrNull(path.description());
            if (title != null) {
                requireConfidence("paths[" + index + "].confidence", path.confidence());
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

    private static void requireConfidence(String field, Double confidence) {
        if (confidence != null && (!Double.isFinite(confidence) || confidence < 0.0 || confidence > 1.0)) {
            throw new IllegalArgumentException(field + " must be a finite number between 0.0 and 1.0.");
        }
    }

    private static void requireNotBlank(String field, String value) {
        if (!notBlank(value)) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
