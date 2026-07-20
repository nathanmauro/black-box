package dev.nathan.sbaagentic.context;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import dev.nathan.sbaagentic.recording.AgentEvent;
import dev.nathan.sbaagentic.memory.MemoryEventReader;
import dev.nathan.sbaagentic.recording.Titles;

import org.springframework.stereotype.Service;

/**
 * The write+query loop that is Black Box's reason to exist. Agents write structured <em>intent</em>
 * — decisions and handoffs — into the recorder, and any later agent (or a future self) reads that
 * intent back, scoped by repo or topic and bounded in time, at runtime and entirely on localhost.
 *
 * <p>Decisions and handoffs are persisted as first-class {@link AgentEvent}s so they live on the
 * same timeline as prompts and tool calls; their structure rides in the event metadata, and
 * {@link #recall} projects that metadata back into the typed {@link RecalledItem} an agent needs.
 * The event log is the source of truth; recall is a projection over it.
 */
@Service
public class ContextService {

    public static final String KIND_DECISION = "decision";
    public static final String KIND_HANDOFF = "handoff";
    public static final String KIND_OBSERVATION = "observation";

    private static final Map<String, String> EVENT_TYPE_BY_KIND = Map.of(
            KIND_DECISION, "Decision",
            KIND_HANDOFF, "Handoff",
            KIND_OBSERVATION, "Observation");

    private static final List<String> DEFAULT_RECALL_KINDS = List.of(KIND_DECISION, KIND_HANDOFF);
    private static final int DEFAULT_WITHIN_HOURS = 168;
    private static final int MAX_WITHIN_HOURS = 24 * 365;
    private static final int RECALL_LIMIT = 50;

    private final MemoryEventReader repository;

    public ContextService(MemoryEventReader repository) {
        this.repository = repository;
    }


    /**
     * Reads prior intent back out. {@code scope} is matched against both the session's working
     * directory (repo) and the captured text, so an agent can recall by where it is working or by
     * what it is working on. A blank scope returns the most recent intent across all repos.
     */
    public RecallResult recall(String scope, int withinHours, List<String> kinds) {
        List<String> resolvedKinds = resolveKinds(kinds);
        List<String> eventTypes = resolvedKinds.stream().map(EVENT_TYPE_BY_KIND::get).toList();
        int hours = withinHours <= 0 ? DEFAULT_WITHIN_HOURS : Math.min(withinHours, MAX_WITHIN_HOURS);
        Instant since = Instant.now().minus(hours, ChronoUnit.HOURS);
        String trimmedScope = scope == null ? null : scope.strip();
        String scopeLike = (trimmedScope == null || trimmedScope.isEmpty())
                ? null
                : "%" + trimmedScope.toLowerCase(Locale.ROOT) + "%";

        List<RecalledItem> items = repository.recall(eventTypes, scopeLike, since, RECALL_LIMIT).stream()
                .map(ContextService::toRecalledItem)
                .toList();
        return new RecallResult(trimmedScope, hours, resolvedKinds, items.size(), items);
    }


    private static List<String> resolveKinds(List<String> kinds) {
        if (kinds == null || kinds.isEmpty()) {
            return DEFAULT_RECALL_KINDS;
        }
        List<String> resolved = new ArrayList<>();
        for (String kind : kinds) {
            if (kind == null) {
                continue;
            }
            String normalized = kind.strip().toLowerCase(Locale.ROOT);
            if (EVENT_TYPE_BY_KIND.containsKey(normalized) && !resolved.contains(normalized)) {
                resolved.add(normalized);
            }
        }
        return resolved.isEmpty() ? DEFAULT_RECALL_KINDS : resolved;
    }

    private static RecalledItem toRecalledItem(AgentEvent event) {
        Map<String, Object> meta = event.metadata() == null ? Map.of() : event.metadata();
        String kind = str(meta.get("kind"));
        if (kind == null) {
            kind = event.eventType() == null ? KIND_OBSERVATION : event.eventType().toLowerCase(Locale.ROOT);
        }
        String headline = switch (kind) {
            case KIND_DECISION -> firstNonBlank(str(meta.get("decision")), Titles.firstLine(event.text()));
            case KIND_HANDOFF -> firstNonBlank(str(meta.get("contextSummary")), Titles.firstLine(event.text()));
            default -> firstNonBlank(Titles.firstLine(event.text()), event.eventType());
        };
        return new RecalledItem(
                event.id(),
                kind,
                event.source(),
                event.clientSessionId(),
                str(meta.get("repo")),
                event.observedAt(),
                headline,
                str(meta.get("rationale")),
                asStringList(meta.get("alternatives")),
                asDouble(meta.get("confidence")),
                asStringList(meta.get("openLoops")),
                str(meta.get("nextAction")),
                str(meta.get("toAgent")));
    }


    private static String firstNonBlank(String first, String second) {
        return notBlank(first) ? first : second;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
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
}
