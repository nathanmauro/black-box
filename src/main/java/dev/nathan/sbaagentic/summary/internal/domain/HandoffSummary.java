package dev.nathan.sbaagentic.summary.internal.domain;

import dev.nathan.sbaagentic.recording.AgentEvent;
import dev.nathan.sbaagentic.recording.EventTypes;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A session's latest handoff, turned into its summary with no model call.
 *
 * <p>A handoff stands for the session only while nothing has moved on from it. Staleness is counted
 * in work, not time: a tab left open for a day after its handoff has done nothing since, while a
 * human turn means the session took a new direction, however soon it came. Replaying real sessions
 * put the work limit where longer limits stop finding more handoffs to use.
 *
 * <p>The ask comes last because the handoff says what happened and rarely what was wanted. Without
 * it, recall on intent fell well behind model summaries; before the handoff, it cost recall on what
 * shipped.
 */
public record HandoffSummary(String text, String context) {

    /** Reaches past {@link #MAX_EVENTS_AFTER} and the lifecycle events between them. */
    public static final int EVENT_WINDOW = 64;

    /** Enough for a session to wind down after its handoff, as a rotation into a new tab does. */
    private static final int MAX_EVENTS_AFTER = 20;

    /** Below this a handoff is a pointer to elsewhere, not an account of the session. */
    private static final int MIN_CONTEXT_CHARS = 300;

    private static final int MAX_ASK_CHARS = 300;
    private static final String HANDOFF = "handoff";
    private static final Set<String> LIFECYCLE = Set.of("sessionstart", "sessionend", "stop", "subagentstop");

    /**
     * @param newestFirst the session's latest {@link #EVENT_WINDOW} events
     * @return the summary, or empty when a model has to read the session instead
     */
    public static Optional<HandoffSummary> from(List<AgentEvent> newestFirst, String firstHumanTurn) {
        int eventsAfter = 0;
        for (AgentEvent event : newestFirst) {
            String type = EventTypes.normalize(event.eventType());
            if (HANDOFF.equals(type)) {

                return standing(event, firstHumanTurn);
            }
            if (LIFECYCLE.contains(type)) {
                continue;
            }
            eventsAfter++;
            if (event.humanText() != null || eventsAfter > MAX_EVENTS_AFTER) {

                return Optional.empty();
            }
        }

        return Optional.empty();
    }

    private static Optional<HandoffSummary> standing(AgentEvent handoff, String firstHumanTurn) {
        Map<String, Object> metadata = handoff.metadata();
        String context = stringField(metadata, "contextSummary");
        if (context.length() < MIN_CONTEXT_CHARS) {

            return Optional.empty();
        }

        StringBuilder text = new StringBuilder(context);
        if (metadata.get("openLoops") instanceof List<?> openLoops && !openLoops.isEmpty()) {
            text.append("\n\nOpen loops:");
            openLoops.forEach(loop -> text.append("\n- ").append(loop));
        }
        String next = stringField(metadata, "nextAction");
        if (!next.isEmpty()) {
            text.append("\n\nNext: ").append(next);
        }
        String ask = ask(firstHumanTurn);
        if (!ask.isEmpty()) {
            text.append("\n\nAsked: ").append(ask);
        }

        return Optional.of(new HandoffSummary(text.toString(), context));
    }

    private static String ask(String firstHumanTurn) {
        String ask = firstHumanTurn == null
                ? ""
                : firstHumanTurn.replaceAll("\\s+", " ").strip();

        return ask.length() <= MAX_ASK_CHARS ? ask : ask.substring(0, MAX_ASK_CHARS);
    }

    private static String stringField(Map<String, Object> metadata, String key) {

        return metadata != null && metadata.get(key) instanceof String value ? value.strip() : "";
    }
}
