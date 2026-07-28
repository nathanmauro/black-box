package dev.nathan.sbaagentic.memory.internal.domain;

import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

import dev.nathan.sbaagentic.recording.AgentEvent;
import dev.nathan.sbaagentic.recording.EventTypes;

public final class EmbeddableText {

    private static final int MAX_CODE_POINTS = 900;
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern HANDOFF_PREFIX = Pattern.compile("^Handoff to [^:]{1,120}:\\s*",
            Pattern.CASE_INSENSITIVE);

    private EmbeddableText() {
    }

    public static String forEvent(AgentEvent event) {
        Objects.requireNonNull(event, "event");
        return forEvent(event.eventType(), event.text(), event.metadata());
    }

    public static String forEvent(String eventType, String text, Map<String, Object> metadata) {
        String source = switch (EventTypes.normalize(eventType)) {
            case "decision" -> firstNonBlank(joinFields(metadata, "decision", "rationale"), text);
            case "handoff" -> firstNonBlank(joinFields(metadata, "contextSummary", "nextAction"), text);
            default -> text;
        };
        return normalize(source);
    }

    public static String forSessionSummary(String summary) {
        return normalize(summary);
    }

    private static String joinFields(Map<String, Object> metadata, String first, String second) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        return firstNonBlank(str(metadata.get(first)), str(metadata.get(second)));
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank() && second != null && !second.isBlank()) {
            return first.strip() + "\n" + second.strip();
        }
        if (first != null && !first.isBlank()) {
            return first;
        }
        if (second != null && !second.isBlank()) {
            return second;
        }
        return null;
    }

    private static String normalize(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String stripped = stripPreamble(text.strip());
        String collapsed = WHITESPACE.matcher(stripped).replaceAll(" ").strip();
        int codePoints = collapsed.codePointCount(0, collapsed.length());
        if (codePoints <= MAX_CODE_POINTS) {
            return collapsed;
        }
        return collapsed.substring(0, collapsed.offsetByCodePoints(0, MAX_CODE_POINTS)).strip();
    }

    private static String stripPreamble(String text) {
        String withoutHandoff = HANDOFF_PREFIX.matcher(text).replaceFirst("");
        int firstLineEnd = withoutHandoff.indexOf('\n');
        String firstLine = firstLineEnd >= 0 ? withoutHandoff.substring(0, firstLineEnd) : withoutHandoff;
        if (firstLine.toLowerCase(java.util.Locale.ROOT).contains("session close-out")) {
            return firstLineEnd >= 0 ? withoutHandoff.substring(firstLineEnd + 1) : "";
        }
        return withoutHandoff;
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
