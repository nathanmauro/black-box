package dev.nathan.sbaagentic.project;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import dev.nathan.sbaagentic.ai.SummaryBackend;
import dev.nathan.sbaagentic.session.AgentSession;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

@Service
public class ProjectMeldService {

    private static final int MAX_SELECTED_SESSIONS = 8;
    private static final int MAX_EVIDENCE_PER_SESSION = 12;
    private static final int MAX_BUNDLE_CHARS = 48_000;
    private static final int MAX_TEXT_CHARS = 1_200;
    private static final int MAX_JSON_CHARS = 900;
    private static final String EXPORT_BUNDLE = "export_bundle";
    private static final String DIRECT = "direct";
    private static final String PROMPT_VERSION = "project-meld-v1";

    private final ProjectRepository repository;
    private final SummaryBackend summaryBackend;

    public ProjectMeldService(ProjectRepository repository, SummaryBackend summaryBackend) {
        this.repository = repository;
        this.summaryBackend = summaryBackend;
    }

    public ProjectMeldPreviewResponse preview(String projectKey, ProjectMeldPreviewRequest request) {
        String canonicalKey = ProjectKeyCodec.decode(projectKey);
        List<String> sessionIds = normalizedSessionIds(request == null ? null : request.sessionIds());
        if (sessionIds.isEmpty()) {
            throw new ResponseStatusException(BAD_REQUEST, "Select at least one project session");
        }
        if (sessionIds.size() > MAX_SELECTED_SESSIONS) {
            throw new ResponseStatusException(BAD_REQUEST,
                    "Select " + MAX_SELECTED_SESSIONS + " sessions or fewer");
        }

        List<AgentSession> sessions = orderedSessions(canonicalKey, sessionIds);
        Map<String, List<ProjectTimelineBlock>> evidence = evidenceBySession(canonicalKey, sessions);
        Bundle bundle = buildBundle(canonicalKey, sessions, evidence);
        String executionMode = executionMode(request.executionMode());
        String provider = firstNonBlank(request.provider(), executionMode.equals(DIRECT) ? "configured-summary" : "local");
        String model = firstNonBlank(request.model(), executionMode.equals(DIRECT) ? "summary-backend" : "context-bundle");
        String preview = bundle.text();
        String status = "bundle";

        if (DIRECT.equals(executionMode)) {
            try {
                preview = summaryBackend.summarize(directPrompt(bundle.text()));
                status = "preview";
            }
            catch (RuntimeException ex) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "Meld preview failed: " + ex.getMessage(), ex);
            }
        }

        return new ProjectMeldPreviewResponse(
                status,
                executionMode,
                provider,
                model,
                ProjectKeyCodec.encode(canonicalKey),
                canonicalKey,
                "Project meld: " + ProjectKeyCodec.labelFor(canonicalKey),
                preview,
                bundle.text(),
                sessions.stream().map(ProjectMeldService::sessionRef).toList(),
                sessions.size(),
                evidence.values().stream().mapToInt(List::size).sum(),
                bundle.text().length(),
                bundle.degradationNotes());
    }

    @Transactional
    public ProjectSavedMeld save(ProjectMeldSaveRequest request) {
        if (request == null) {
            throw new ResponseStatusException(BAD_REQUEST, "Meld save request is required");
        }
        String canonicalKey = ProjectKeyCodec.decode(request.projectKey());
        List<String> sessionIds = normalizedSessionIds(request.sessionIds());
        if (sessionIds.isEmpty()) {
            throw new ResponseStatusException(BAD_REQUEST, "Select at least one project session");
        }
        if (sessionIds.size() > MAX_SELECTED_SESSIONS) {
            throw new ResponseStatusException(BAD_REQUEST,
                    "Select " + MAX_SELECTED_SESSIONS + " sessions or fewer");
        }
        List<AgentSession> sessions = orderedSessions(canonicalKey, sessionIds);
        String id = UUID.randomUUID().toString();
        String title = firstNonBlank(request.title(), "Project meld: " + ProjectKeyCodec.labelFor(canonicalKey));
        String body = requiredText(request.body(), "Meld body is required");
        String provider = firstNonBlank(request.provider(), "local");
        String model = firstNonBlank(request.model(), "context-bundle");
        String promptVersion = firstNonBlank(request.promptVersion(), PROMPT_VERSION);
        String executionMode = executionMode(request.executionMode());
        boolean savedFromPreview = request.savedFromPreview() == null || request.savedFromPreview();
        Map<String, Object> metadata = request.metadata() == null ? Map.of() : Map.copyOf(request.metadata());
        Instant createdAt = Instant.now();

        repository.insertSavedMeld(
                id,
                canonicalKey,
                title,
                body,
                provider,
                model,
                promptVersion,
                executionMode,
                savedFromPreview,
                metadata,
                createdAt,
                sessions);
        return new ProjectSavedMeld(
                id,
                ProjectKeyCodec.encode(canonicalKey),
                canonicalKey,
                title,
                body,
                provider,
                model,
                promptVersion,
                executionMode,
                savedFromPreview,
                metadata,
                createdAt,
                sessions.stream().map(ProjectMeldService::sessionRef).toList());
    }

    private List<AgentSession> orderedSessions(String canonicalKey, List<String> sessionIds) {
        List<AgentSession> found = repository.sessionsForProjectByIds(canonicalKey, sessionIds);
        Map<String, AgentSession> byId = found.stream()
                .collect(Collectors.toMap(AgentSession::id, session -> session));
        List<String> missing = sessionIds.stream()
                .filter(id -> !byId.containsKey(id))
                .toList();
        if (!missing.isEmpty()) {
            throw new ResponseStatusException(BAD_REQUEST,
                    "Selected sessions must belong to this project: " + String.join(", ", missing));
        }
        return sessionIds.stream().map(byId::get).toList();
    }

    private Map<String, List<ProjectTimelineBlock>> evidenceBySession(String canonicalKey, List<AgentSession> sessions) {
        Map<String, List<ProjectTimelineBlock>> evidence = new LinkedHashMap<>();
        for (AgentSession session : sessions) {
            evidence.put(session.id(),
                    repository.timelineBlocksForSession(canonicalKey, session.id(), MAX_EVIDENCE_PER_SESSION));
        }
        return evidence;
    }

    private Bundle buildBundle(
            String canonicalKey,
            List<AgentSession> sessions,
            Map<String, List<ProjectTimelineBlock>> evidence) {
        List<String> degradationNotes = new ArrayList<>();
        StringBuilder builder = new StringBuilder();
        builder.append("# Project Meld Bundle\n\n");
        builder.append("- Project: ").append(ProjectKeyCodec.labelFor(canonicalKey)).append('\n');
        builder.append("- Canonical key: ").append(canonicalKey).append('\n');
        builder.append("- Selected sessions: ").append(sessions.size()).append('\n');
        builder.append("- Bundle policy: summaries first, then bounded high-signal evidence.\n\n");

        List<AgentSession> chronological = sessions.stream()
                .sorted(Comparator.comparing(AgentSession::startedAt))
                .toList();
        int index = 1;
        for (AgentSession session : chronological) {
            builder.append("## Session ").append(index++).append(": ")
                    .append(firstNonBlank(session.title(), session.clientSessionId())).append("\n\n");
            builder.append("- Session id: ").append(session.id()).append('\n');
            builder.append("- Client session id: ").append(session.clientSessionId()).append('\n');
            builder.append("- Source: ").append(session.source()).append('\n');
            builder.append("- CWD: ").append(firstNonBlank(session.cwd(), "(none)")).append('\n');
            builder.append("- Started: ").append(session.startedAt()).append('\n');
            builder.append("- Last seen: ").append(session.lastSeenAt()).append('\n');
            builder.append("- Events: ").append(session.eventCount()).append("\n\n");

            if (session.summary() == null || session.summary().isBlank()) {
                builder.append("### Summary\n\n");
                builder.append("(No saved summary for this session.)\n\n");
                degradationNotes.add("Missing summary for " + session.clientSessionId());
            }
            else {
                builder.append("### Summary\n\n");
                appendClipped(builder, session.summary().strip(), MAX_TEXT_CHARS);
                builder.append("\n\n");
            }

            builder.append("### Evidence\n\n");
            List<ProjectTimelineBlock> blocks = evidence.getOrDefault(session.id(), List.of());
            if (blocks.isEmpty()) {
                builder.append("- No high-signal evidence found in the bounded storyline query.\n\n");
                continue;
            }
            for (ProjectTimelineBlock block : blocks) {
                appendEvidence(builder, block);
            }
            builder.append('\n');
        }

        String text = builder.toString().stripTrailing();
        if (text.length() > MAX_BUNDLE_CHARS) {
            String marker = "\n\n[Bundle clipped to " + MAX_BUNDLE_CHARS + " characters.]";
            text = text.substring(0, MAX_BUNDLE_CHARS - marker.length()) + marker;
            degradationNotes.add("Bundle clipped to " + MAX_BUNDLE_CHARS + " characters");
        }
        return new Bundle(text, List.copyOf(degradationNotes));
    }

    private static void appendEvidence(StringBuilder builder, ProjectTimelineBlock block) {
        builder.append("- [").append(block.observedAt()).append("] ")
                .append(block.blockType()).append(" / ")
                .append(firstNonBlank(block.eventType(), "event"))
                .append(" / ")
                .append(firstNonBlank(block.source(), "unknown"))
                .append(": ")
                .append(firstNonBlank(block.headline(), "(untitled evidence)"))
                .append('\n');
        if (block.text() != null && !block.text().isBlank()) {
            builder.append("  Text: ");
            appendClipped(builder, block.text().strip(), MAX_TEXT_CHARS);
            builder.append('\n');
        }
        if (block.toolName() != null && !block.toolName().isBlank()) {
            builder.append("  Tool: ").append(block.toolName()).append('\n');
        }
        if (block.toolInputJson() != null && !block.toolInputJson().isBlank()) {
            builder.append("  Input: ");
            appendClipped(builder, block.toolInputJson().strip(), MAX_JSON_CHARS);
            builder.append('\n');
        }
        if (block.toolOutputJson() != null && !block.toolOutputJson().isBlank()) {
            builder.append("  Output: ");
            appendClipped(builder, block.toolOutputJson().strip(), MAX_JSON_CHARS);
            builder.append('\n');
        }
    }

    private static void appendClipped(StringBuilder builder, String value, int maxChars) {
        if (value.length() <= maxChars) {
            builder.append(value);
            return;
        }
        int keep = Math.max(0, maxChars - 28);
        builder.append(value, 0, keep).append(" [clipped]");
    }

    private static String directPrompt(String bundle) {
        return """
                Create a Black Box project meld from this bounded context bundle.

                Requirements:
                - Summarize the project state across sessions.
                - Preserve concrete decisions, files, commands, and open loops when present.
                - Separate observed facts from synthesis.
                - End with the next useful action.

                """ + bundle;
    }

    private static List<String> normalizedSessionIds(List<String> sessionIds) {
        if (sessionIds == null) {
            return List.of();
        }
        Set<String> ids = new LinkedHashSet<>();
        for (String id : sessionIds) {
            if (id != null && !id.isBlank()) {
                ids.add(id.strip());
            }
        }
        return List.copyOf(ids);
    }

    private static String executionMode(String value) {
        if (DIRECT.equalsIgnoreCase(value)) {
            return DIRECT;
        }
        return EXPORT_BUNDLE;
    }

    private static ProjectMeldSessionRef sessionRef(AgentSession session) {
        return new ProjectMeldSessionRef(
                session.id(),
                session.source(),
                session.clientSessionId(),
                session.title(),
                session.cwd(),
                session.eventCount(),
                session.startedAt(),
                session.lastSeenAt());
    }

    private static String firstNonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String requiredText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(BAD_REQUEST, message);
        }
        return value.strip();
    }

    private record Bundle(String text, List<String> degradationNotes) {
    }
}
