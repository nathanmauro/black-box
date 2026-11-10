package dev.nathan.sbaagentic.recording.internal.application;

import dev.nathan.sbaagentic.recording.AgentEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/** Request-local lookup so every candidate does not normalize the entire recorded conversation. */
final class RecordedConversationIndex {
    private static final Duration DUPLICATE_WINDOW = Duration.ofMinutes(2);
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern TYPE_SEPARATORS = Pattern.compile("[^a-z0-9]");

    private final Map<MessageKey, Occurrences> messages = new HashMap<>();

    RecordedConversationIndex(List<AgentEvent> recorded) {
        for (AgentEvent event : recorded) {
            String role = conversationRole(event);
            if (role == null) {
                continue;
            }
            MessageKey key = new MessageKey(role, normalize(event.text()));
            Occurrences occurrences = messages.computeIfAbsent(key, ignored -> new Occurrences());
            if (event.turnId() != null) {
                occurrences.turnIds.add(event.turnId());
            }
            occurrences.times.add(event.observedAt());
        }
    }

    boolean contains(AgentEvent transcript) {
        Occurrences occurrences = messages.get(new MessageKey(transcript.role(), normalize(transcript.text())));
        if (occurrences == null) {

            return false;
        }
        if (transcript.turnId() != null && occurrences.turnIds.contains(transcript.turnId())) {

            return true;
        }

        // Compare the closest times on either side; avoid overflowing Instant at its bounds.
        Instant observedAt = transcript.observedAt();
        Instant before = occurrences.times.floor(observedAt);
        if (before != null && Duration.between(before, observedAt).compareTo(DUPLICATE_WINDOW) <= 0) {

            return true;
        }
        Instant after = occurrences.times.ceiling(observedAt);

        return after != null && Duration.between(observedAt, after).compareTo(DUPLICATE_WINDOW) <= 0;
    }

    private static String conversationRole(AgentEvent event) {
        String role = lower(event.role());
        if ("user".equals(role) || "assistant".equals(role)) {

            return role;
        }
        String type = TYPE_SEPARATORS.matcher(lower(event.eventType())).replaceAll("");
        if (type.equals("userpromptsubmit") || type.equals("beforesubmitprompt")) {

            return "user";
        }
        if (event.text() != null
                && !event.text().isBlank()
                && (type.equals("stop")
                        || type.equals("assistantmessage")
                        || type.equals("agentmessage")
                        || type.equals("agentresponse")
                        || type.equals("finalresponse"))) {

            return "assistant";
        }

        return null;
    }

    private static String normalize(String text) {

        return WHITESPACE.matcher(lower(text)).replaceAll(" ").trim();
    }

    private static String lower(String value) {

        return (value == null ? "" : value).toLowerCase(Locale.ROOT);
    }

    private record MessageKey(String role, String text) {}

    private static final class Occurrences {
        private final Set<String> turnIds = new HashSet<>();
        private final NavigableSet<Instant> times = new TreeSet<>();
    }
}
