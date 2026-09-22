package dev.nathan.sbaagentic.judgment.internal.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.nathan.sbaagentic.judgment.internal.domain.Beat;
import dev.nathan.sbaagentic.judgment.internal.domain.BeatEvent;
import dev.nathan.sbaagentic.recording.AgentEvent;
import dev.nathan.sbaagentic.recording.EventTypes;

public class BeatFolder {

    private static final Set<String> CLOSERS = Set.of(
            "userpromptsubmit",
            "decision",
            "handoff",
            "observation",
            "projection",
            "subagentstart",
            "subagentstop",
            "sessionstart",
            "sessionend",
            "stop",
            "manualcapture",
            "quicknote");

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;
    private final long gapMs;
    private final int maxEvents;
    private final int maxChars;
    private final Map<String, MutableBeat> openBeats = new LinkedHashMap<>();

    public BeatFolder(ObjectMapper objectMapper, long gapMs, int maxEvents, int maxChars) {
        this.objectMapper = objectMapper;
        this.gapMs = gapMs;
        this.maxEvents = maxEvents;
        this.maxChars = maxChars;
    }

    public synchronized FoldResult fold(AgentEvent event) {
        BeatEvent beatEvent = BeatEvent.from(event);
        String normalizedType = EventTypes.normalize(event.eventType());
        String line = lineFor(event);
        boolean closer = CLOSERS.contains(normalizedType);
        MutableBeat open = openBeats.get(event.sessionId());
        Beat closed = null;
        if (open != null
                && (Duration.between(open.endedAt, event.observedAt()).toMillis() > gapMs
                        || open.events.size() >= maxEvents
                        || open.textLength() + line.length() > maxChars
                        || closer)) {
            closed = open.freeze();
            openBeats.remove(event.sessionId());
            open = null;
        }
        if (open == null) {
            open = new MutableBeat(beatId(event.sessionId(), event.id()), event.sessionId(), event.observedAt());
        }
        open.add(beatEvent, line);
        if (closer) {
            openBeats.remove(event.sessionId());
            return new FoldResult(Optional.ofNullable(closed), Optional.of(open.freeze()));
        }
        openBeats.put(event.sessionId(), open);
        return new FoldResult(Optional.ofNullable(closed), Optional.empty());
    }

    public synchronized List<Beat> flushQuiet(Instant now) {
        List<String> quiet = openBeats.values().stream()
                .filter(beat -> Duration.between(beat.endedAt, now).toMillis() > gapMs)
                .map(beat -> beat.sessionId)
                .toList();
        List<Beat> flushed = new ArrayList<>();
        for (String sessionId : quiet) {
            MutableBeat beat = openBeats.remove(sessionId);
            if (beat != null) {
                flushed.add(beat.freeze());
            }
        }
        return flushed;
    }

    public String lineFor(AgentEvent event) {
        String normalizedType = EventTypes.normalize(event.eventType());
        String text = trim(event.text());
        if ("userpromptsubmit".equals(normalizedType)) {
            return "Nathan: " + clip(text, 400);
        }
        if (event.toolName() != null && !event.toolName().isBlank()) {
            String argument = toolArgument(event.toolInputJson());
            String output = firstOutputLine(event.toolOutputJson(), text);
            return event.toolName() + "(" + clip(argument, 160) + ")"
                    + (output.isBlank() ? "" : " → " + clip(output, 120));
        }
        if ("subagentstart".equals(normalizedType)) {
            Object agentType = event.metadata() == null ? null : event.metadata().get("agentType");
            return "spawned " + (agentType instanceof String value && !value.isBlank() ? value : "agent");
        }
        if ("subagentstop".equals(normalizedType)) {
            return "agent returned";
        }
        return event.eventType() + (text.isBlank() ? "" : ": " + clip(text, 300));
    }

    public boolean isCloser(String eventType) {
        return CLOSERS.contains(EventTypes.normalize(eventType));
    }

    private String toolArgument(String toolInputJson) {
        if (toolInputJson == null || toolInputJson.isBlank()) {
            return "";
        }
        try {
            Map<String, Object> input = objectMapper.readValue(toolInputJson, MAP_TYPE);
            for (String key : List.of("command", "file_path", "pattern", "prompt", "description", "query")) {
                Object value = input.get(key);
                if (value != null) {
                    return String.valueOf(value);
                }
            }
            return objectMapper.writeValueAsString(input);
        }
        catch (Exception ignored) {
            return toolInputJson;
        }
    }

    private String firstOutputLine(String toolOutputJson, String text) {
        String raw = toolOutputJson == null || toolOutputJson.isBlank() ? text : toolOutputJson;
        if (raw == null || raw.isBlank()) {
            return "";
        }
        try {
            Map<String, Object> output = objectMapper.readValue(raw, MAP_TYPE);
            for (String key : List.of("stdout", "output", "result", "content")) {
                Object value = output.get(key);
                if (value != null) {
                    raw = String.valueOf(value);
                    break;
                }
            }
        }
        catch (Exception ignored) {
            // Keep the raw output.
        }
        for (String line : raw.split("\\R")) {
            String trimmed = line.trim();
            if (!trimmed.isBlank()) {
                return trimmed;
            }
        }
        return "";
    }

    private static String beatId(String sessionId, String firstEventId) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] hashed = digest.digest((sessionId + "|" + firstEventId).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed, 0, 8);
        }
        catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-1 unavailable", ex);
        }
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static String clip(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, Math.max(0, max - 3)) + "...";
    }

    public record FoldResult(Optional<Beat> closed, Optional<Beat> standalone) {
    }

    private static final class MutableBeat {
        private final String id;
        private final String sessionId;
        private final Instant startedAt;
        private Instant endedAt;
        private final List<BeatEvent> events = new ArrayList<>();
        private final List<String> lines = new ArrayList<>();

        private MutableBeat(String id, String sessionId, Instant startedAt) {
            this.id = id;
            this.sessionId = sessionId;
            this.startedAt = startedAt;
            this.endedAt = startedAt;
        }

        private void add(BeatEvent event, String line) {
            events.add(event);
            lines.add(line);
            if (event.observedAt().isAfter(endedAt)) {
                endedAt = event.observedAt();
            }
        }

        private int textLength() {
            return String.join("\n", lines).length();
        }

        private Beat freeze() {
            return new Beat(
                    id,
                    sessionId,
                    startedAt,
                    endedAt,
                    List.copyOf(events),
                    List.copyOf(lines),
                    String.join("\n", lines));
        }
    }
}
