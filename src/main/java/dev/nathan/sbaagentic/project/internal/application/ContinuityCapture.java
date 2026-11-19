package dev.nathan.sbaagentic.project.internal.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nathan.sbaagentic.project.ProjectAliasSnapshot;
import dev.nathan.sbaagentic.project.internal.application.port.ContinuityStore.CaptureRow;
import dev.nathan.sbaagentic.project.internal.domain.ProjectKeyCodec;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A structured capture with its metadata parsed and its project resolved. The project is the
 * capture's own {@code repo} when it names one, else its session's working directory, resolved
 * through project aliases.
 */
record ContinuityCapture(
        String eventId,
        String sessionId,
        String eventType,
        Instant observedAt,
        String project,
        JsonNode metadata,
        String text) {

    static final String DECISION = "Decision";
    static final String HANDOFF = "Handoff";
    static final String OBSERVATION = "Observation";

    static List<ContinuityCapture> from(List<CaptureRow> rows, ObjectMapper json, ProjectAliasSnapshot aliases) {
        List<ContinuityCapture> captures = new ArrayList<>(rows.size());
        for (CaptureRow row : rows) {
            JsonNode metadata = parse(json, row.metadataJson());
            String repo = metadata.path("repo").asText("");
            String scope = repo.isBlank() ? row.sessionCwd() : repo;
            captures.add(new ContinuityCapture(
                    row.eventId(),
                    row.sessionId(),
                    row.eventType(),
                    row.observedAt(),
                    projectOf(scope, aliases),
                    metadata,
                    row.text()));
        }

        return captures;
    }

    static String projectOf(String scope, ProjectAliasSnapshot aliases) {
        String canonical = ProjectKeyCodec.canonicalize(scope);
        if (ProjectKeyCodec.NO_PROJECT_KEY.equals(canonical)) {

            return canonical;
        }

        return aliases.resolve(canonical);
    }

    boolean is(String type) {

        return type.equals(eventType);
    }

    boolean scoped() {

        return !ProjectKeyCodec.NO_PROJECT_KEY.equals(project);
    }

    String field(String name) {
        JsonNode value = metadata.path(name);

        return value.isValueNode() && !value.isNull() ? value.asText() : null;
    }

    /** Nonblank entries of a string-array metadata field. */
    List<String> list(String name) {
        JsonNode value = metadata.path(name);
        if (!value.isArray()) {

            return List.of();
        }
        List<String> items = new ArrayList<>();
        for (JsonNode item : value) {
            String text = item.asText("");
            if (!text.isBlank()) {
                items.add(text);
            }
        }

        return items;
    }

    private static JsonNode parse(ObjectMapper json, String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) {

            return json.createObjectNode();
        }
        try {
            JsonNode node = json.readTree(metadataJson);

            return node != null && node.isObject() ? node : json.createObjectNode();
        } catch (com.fasterxml.jackson.core.JsonProcessingException malformed) {

            // A malformed row is linted as if it carried no metadata rather than failing the scan.
            return json.createObjectNode();
        }
    }
}
