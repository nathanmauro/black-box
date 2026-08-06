package dev.nathan.sbaagentic.project.internal.application;

import dev.nathan.sbaagentic.project.internal.domain.ProjectKeyCodec;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import dev.nathan.sbaagentic.project.ProjectGraphOperations;
import dev.nathan.sbaagentic.project.ProjectScopeOperations;
import dev.nathan.sbaagentic.project.ProjectTrajectoryResponse;
import dev.nathan.sbaagentic.project.TrajectoryCapture;
import dev.nathan.sbaagentic.project.TrajectoryPath;
import dev.nathan.sbaagentic.project.TrajectoryTask;
import dev.nathan.sbaagentic.project.internal.application.port.ProjectGraphStore;
import dev.nathan.sbaagentic.project.internal.application.port.ProjectGraphStore.CaptureRow;
import dev.nathan.sbaagentic.project.internal.application.port.ProjectGraphStore.TaskRow;

import org.springframework.stereotype.Service;

@Service
public class ProjectGraphService implements ProjectGraphOperations {

    private static final int CAPTURE_LIMIT = 120;
    private static final int TASK_LIMIT = 120;
    private static final String SOURCE_TYPE_SAVED_MELD = "saved_meld";
    private static final String KIND_DECISION = "decision";
    private static final String KIND_HANDOFF = "handoff";
    private static final String KIND_OBSERVATION = "observation";
    private static final String KIND_MELD = "meld";
    private static final String KIND_PROJECTION = "projection";

    private final ProjectGraphStore store;
    private final ProjectScopeOperations aliasService;

    public ProjectGraphService(ProjectGraphStore store, ProjectScopeOperations aliasService) {
        this.store = store;
        this.aliasService = aliasService;
    }

    public ProjectTrajectoryResponse graph(String projectKey) {
        String canonicalKey = aliasService.resolve(ProjectKeyCodec.decode(projectKey));
        List<TrajectoryCapture> captures = store.recentCaptures(canonicalKey, CAPTURE_LIMIT).stream()
                .map(ProjectGraphService::toCapture)
                .sorted(Comparator.comparing(
                        TrajectoryCapture::observedAt,
                        Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(TrajectoryCapture::id, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(CAPTURE_LIMIT)
                .toList();
        List<TrajectoryTask> tasks = store.openTasks(canonicalKey, TASK_LIMIT).stream()
                .limit(TASK_LIMIT)
                .map(ProjectGraphService::toTask)
                .toList();
        return new ProjectTrajectoryResponse(
                ProjectKeyCodec.encode(canonicalKey),
                canonicalKey,
                ProjectKeyCodec.labelFor(canonicalKey),
                Instant.now(),
                store.totalCaptures(canonicalKey),
                captures,
                tasks);
    }

    private static TrajectoryCapture toCapture(CaptureRow row) {
        Map<String, Object> metadata = row.metadata() == null ? Map.of() : row.metadata();
        List<TrajectoryPath> paths = paths(metadata.get("paths"));
        String kind = kind(row, metadata);
        String fallbackHeadline = firstNonBlank(
                row.headline(),
                firstNonBlank(firstLine(row.text()), row.eventType()));
        String headline = switch (kind) {
            case KIND_DECISION -> firstNonBlank(str(metadata.get("decision")), fallbackHeadline);
            case KIND_HANDOFF -> firstNonBlank(str(metadata.get("contextSummary")), fallbackHeadline);
            case KIND_PROJECTION -> firstNonBlank(firstPathTitle(paths), fallbackHeadline);
            default -> fallbackHeadline;
        };
        return new TrajectoryCapture(
                row.id(),
                kind,
                row.sessionId(),
                row.sessionTitle(),
                row.clientSessionId(),
                row.source(),
                headline,
                row.text(),
                str(metadata.get("rationale")),
                asStringList(metadata.get("alternatives")),
                asStringList(metadata.get("openLoops")),
                str(metadata.get("nextAction")),
                str(metadata.get("toAgent")),
                asDouble(metadata.get("confidence")),
                paths,
                row.observedAt());
    }

    private static TrajectoryTask toTask(TaskRow row) {
        return new TrajectoryTask(
                row.id(),
                row.title(),
                row.status(),
                row.priority(),
                row.updatedAt());
    }

    private static String kind(CaptureRow row, Map<String, Object> metadata) {
        if (SOURCE_TYPE_SAVED_MELD.equals(row.sourceType())) {
            return KIND_MELD;
        }
        String metadataKind = knownKind(str(metadata.get("kind")));
        if (metadataKind != null) {
            return metadataKind;
        }
        String eventKind = knownKind(row.eventType());
        return eventKind == null ? KIND_OBSERVATION : eventKind;
    }

    private static String knownKind(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.strip().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case KIND_DECISION, KIND_HANDOFF, KIND_OBSERVATION, KIND_PROJECTION -> normalized;
            default -> null;
        };
    }

    private static List<TrajectoryPath> paths(Object value) {
        if (!(value instanceof List<?> list)) {
            return null;
        }
        List<TrajectoryPath> paths = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                String title = str(map.get("title"));
                String description = str(map.get("description"));
                Double confidence = asDouble(map.get("confidence"));
                if (notBlank(title) || notBlank(description) || confidence != null) {
                    paths.add(new TrajectoryPath(title, description, confidence));
                }
            }
        }
        return paths.isEmpty() ? null : paths;
    }

    private static String firstPathTitle(List<TrajectoryPath> paths) {
        if (paths == null) {
            return null;
        }
        for (TrajectoryPath path : paths) {
            if (notBlank(path.title())) {
                return path.title();
            }
        }
        return null;
    }

    private static List<String> asStringList(Object value) {
        if (value instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object element : list) {
                if (element != null) {
                    out.add(String.valueOf(element));
                }
            }
            return out.isEmpty() ? null : out;
        }
        return null;
    }

    private static Double asDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Double.parseDouble(text.trim());
            }
            catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static String firstNonBlank(String first, String second) {
        return notBlank(first) ? first : second;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String firstLine(String value) {
        if (value == null) {
            return null;
        }
        int newline = value.indexOf('\n');
        return newline >= 0 ? value.substring(0, newline) : value;
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
