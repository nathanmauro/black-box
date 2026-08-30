package dev.nathan.sbaagentic.recording.internal.adapter.out.file;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.nathan.sbaagentic.recording.AgentEvent;
import dev.nathan.sbaagentic.recording.AgentSession;
import dev.nathan.sbaagentic.recording.TranscriptProperties;
import dev.nathan.sbaagentic.recording.internal.application.RedactionService;
import dev.nathan.sbaagentic.recording.internal.application.port.TranscriptMessageSource;
import dev.nathan.sbaagentic.recording.internal.application.port.TranscriptRead;

import org.springframework.stereotype.Component;

/** Reads only human-visible user/assistant text from known Codex and Claude JSONL transcripts. */
@Component
public class JsonlTranscriptMessageSource implements TranscriptMessageSource {

    static final int PARSER_VERSION = 1;
    private static final Duration DUPLICATE_WINDOW = Duration.ofSeconds(5);

    private final ObjectMapper objectMapper;
    private final RedactionService redaction;
    private final TranscriptProperties properties;
    private final Map<CacheKey, CacheEntry> cache;

    public JsonlTranscriptMessageSource(
            ObjectMapper objectMapper,
            RedactionService redaction,
            TranscriptProperties properties) {
        this.objectMapper = objectMapper;
        this.redaction = redaction;
        this.properties = properties;
        this.cache = new LinkedHashMap<>(16, 0.75f, true);
    }

    @Override
    public TranscriptRead read(AgentSession session, List<String> candidatePaths) {
        if (candidatePaths == null || candidatePaths.isEmpty()) {
            return TranscriptRead.unavailable("not-recorded");
        }
        String source = normalizedSource(session.source());
        List<String> roots = switch (source) {
            case "codex" -> properties.getCodexRoots();
            case "claude" -> properties.getClaudeRoots();
            default -> null;
        };
        if (roots == null) {
            return TranscriptRead.unavailable("unsupported-source");
        }
        String lastReason = "not-readable";
        for (String candidate : candidatePaths) {
            try {
                Path path = confinedPath(candidate, roots);
                BasicFileAttributes attributes = Files.readAttributes(
                        path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (!attributes.isRegularFile() || !path.getFileName().toString().endsWith(".jsonl")) {
                    lastReason = "not-a-transcript-file";
                    continue;
                }
                if (attributes.size() > Math.max(1, properties.getMaxBytes())) {
                    lastReason = "transcript-too-large";
                    continue;
                }

                ParsedTranscript parsed = cachedOrParse(path, attributes, source);
                if (!ownsTranscript(session, path, parsed)) {
                    lastReason = "identity-mismatch";
                    continue;
                }
                List<AgentEvent> messages = parsed.messages().stream()
                        .map(message -> toEvent(session, message))
                        .toList();
                return new TranscriptRead(true, parsed.complete(), parsed.reason(), messages);
            }
            catch (IOException | RuntimeException ex) {
                lastReason = "not-readable";
            }
        }
        return TranscriptRead.unavailable(lastReason);
    }

    private Path confinedPath(String candidate, List<String> roots) throws IOException {
        if (candidate == null || candidate.isBlank()) {
            throw new IOException("Blank transcript path");
        }
        Path path = Path.of(candidate).toRealPath();
        for (String rootValue : roots) {
            if (rootValue == null || rootValue.isBlank()) continue;
            Path root = Path.of(rootValue).toAbsolutePath().normalize();
            if (Files.exists(root)) {
                root = root.toRealPath();
            }
            if (path.startsWith(root)) {
                return path;
            }
        }
        throw new IOException("Transcript path is outside configured roots");
    }

    private ParsedTranscript cachedOrParse(
            Path path, BasicFileAttributes attributes, String source) throws IOException {
        CacheKey key = new CacheKey(path, source);
        CacheStamp stamp = new CacheStamp(
                String.valueOf(attributes.fileKey()),
                attributes.size(),
                attributes.lastModifiedTime().toMillis(),
                PARSER_VERSION);
        synchronized (cache) {
            CacheEntry hit = cache.get(key);
            if (hit != null && hit.stamp().equals(stamp)) {
                return hit.transcript();
            }
        }

        ParsedTranscript parsed = parse(path, source);
        synchronized (cache) {
            cache.put(key, new CacheEntry(stamp, parsed));
            int maxEntries = Math.max(1, properties.getCacheEntries());
            while (cache.size() > maxEntries) {
                CacheKey eldest = cache.keySet().iterator().next();
                cache.remove(eldest);
            }
        }
        return parsed;
    }

    private ParsedTranscript parse(Path path, String source) throws IOException {
        return switch (source) {
            case "claude" -> parseClaude(path);
            case "codex" -> parseCodex(path);
            default -> new ParsedTranscript(null, null, Set.of(), List.of(), 0, 0, 0);
        };
    }

    private ParsedTranscript parseCodex(Path path) throws IOException {
        List<ParsedMessage> messages = new ArrayList<>();
        String transcriptId = null;
        String parentId = null;
        String currentTurnId = null;
        ParseIssues issues = new ParseIssues();
        long ordinal = 0;
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            for (String line; (line = reader.readLine()) != null;) {
                ordinal++;
                if (line.isBlank()) continue;
                JsonNode root;
                try {
                    root = objectMapper.readTree(line);
                }
                catch (IOException ex) {
                    issues.malformedLines++;
                    continue;
                }
                String type = text(root, "type");
                JsonNode payload = root.path("payload");
                if ("session_meta".equals(type)) {
                    transcriptId = firstText(payload, "id", "session_id");
                    parentId = firstText(payload, "parent_thread_id", "forked_from_id", "session_id");
                    continue;
                }
                if ("turn_context".equals(type)) {
                    currentTurnId = firstText(payload, "turn_id", "id");
                    continue;
                }
                ParsedMessage message = null;
                if ("response_item".equals(type) && "message".equals(text(payload, "type"))) {
                    String role = text(payload, "role");
                    if ("assistant".equals(role)) {
                        String body = contentText(payload.path("content"), role);
                        message = parsedMessage(
                                root, payload, currentTurnId, role, body, ordinal, 2, issues);
                    }
                }
                else if ("event_msg".equals(type)) {
                    String payloadType = text(payload, "type");
                    String role = switch (payloadType) {
                        case "user_message" -> "user";
                        case "agent_message" -> "assistant";
                        default -> null;
                    };
                    message = parsedMessage(
                            root, payload, currentTurnId, role, text(payload, "message"), ordinal, 1, issues);
                }
                if (message != null) messages.add(message);
            }
        }
        return new ParsedTranscript(
                transcriptId,
                parentId,
                Set.of(),
                deduplicate(messages),
                issues.malformedLines,
                issues.invalidTimestamps,
                issues.clippedMessages);
    }

    private ParsedTranscript parseClaude(Path path) throws IOException {
        List<ParsedMessage> messages = new ArrayList<>();
        Set<String> agentIds = new LinkedHashSet<>();
        String transcriptId = null;
        ParseIssues issues = new ParseIssues();
        long ordinal = 0;
        String currentTurnId = null;
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            for (String line; (line = reader.readLine()) != null;) {
                ordinal++;
                if (line.isBlank()) continue;
                JsonNode root;
                try {
                    root = objectMapper.readTree(line);
                }
                catch (IOException ex) {
                    issues.malformedLines++;
                    continue;
                }
                String rowSessionId = text(root, "sessionId");
                if (transcriptId == null && rowSessionId != null) transcriptId = rowSessionId;
                String agentId = text(root, "agentId");
                if (agentId != null) agentIds.add(agentId);

                String type = text(root, "type");
                if (!"user".equals(type) && !"assistant".equals(type)) continue;
                if (root.path("isMeta").asBoolean(false)) continue;
                JsonNode messageNode = root.path("message");
                String role = firstText(messageNode, "role");
                if (!"user".equals(role) && !"assistant".equals(role)) role = type;
                String body = contentText(messageNode.path("content"), role);
                if (body == null || body.isBlank()) continue;
                if ("user".equals(role)) {
                    currentTurnId = firstText(root, "promptId", "uuid");
                }
                ParsedMessage parsed = parsedMessage(
                        root, messageNode, currentTurnId, role, body, ordinal, 2, issues);
                if (parsed != null) messages.add(parsed);
            }
        }
        return new ParsedTranscript(
                transcriptId,
                null,
                Set.copyOf(agentIds),
                deduplicate(messages),
                issues.malformedLines,
                issues.invalidTimestamps,
                issues.clippedMessages);
    }

    private ParsedMessage parsedMessage(
            JsonNode root,
            JsonNode payload,
            String turnId,
            String role,
            String body,
            long ordinal,
            int priority,
            ParseIssues issues) {
        if (!("user".equals(role) || "assistant".equals(role)) || body == null || body.isBlank()) {
            return null;
        }
        Instant observedAt = instant(text(root, "timestamp"));
        if (observedAt == null) {
            issues.invalidTimestamps++;
            return null;
        }
        String sourceId = firstText(payload, "id", "uuid");
        if (sourceId == null) sourceId = firstText(root, "uuid", "id");
        if (sourceId == null) sourceId = Long.toString(ordinal);
        if (redaction.clips(body)) {
            issues.clippedMessages++;
        }
        return new ParsedMessage(
                sourceId,
                turnId,
                role,
                redaction.redact(body),
                observedAt,
                ordinal,
                priority);
    }

    private static List<ParsedMessage> deduplicate(List<ParsedMessage> input) {
        input.sort(Comparator.comparing(ParsedMessage::observedAt).thenComparingLong(ParsedMessage::ordinal));
        List<ParsedMessage> kept = new ArrayList<>();
        for (ParsedMessage candidate : input) {
            int lastIndex = kept.size() - 1;
            if (lastIndex >= 0 && duplicates(kept.get(lastIndex), candidate)) {
                if (candidate.priority() > kept.get(lastIndex).priority()) {
                    kept.set(lastIndex, candidate);
                }
                continue;
            }
            kept.add(candidate);
        }
        return List.copyOf(kept);
    }

    private static boolean duplicates(ParsedMessage left, ParsedMessage right) {
        if (!left.role().equals(right.role())) return false;
        if (!normalize(left.text()).equals(normalize(right.text()))) return false;
        if (left.turnId() != null && left.turnId().equals(right.turnId())) return true;
        return Duration.between(left.observedAt(), right.observedAt()).abs().compareTo(DUPLICATE_WINDOW) <= 0;
    }

    private static String contentText(JsonNode content, String role) {
        if (content.isTextual()) return content.asText();
        if (!content.isArray()) return null;
        List<String> parts = new ArrayList<>();
        for (JsonNode block : content) {
            String type = text(block, "type");
            boolean visible = "text".equals(type)
                    || ("user".equals(role) && "input_text".equals(type))
                    || ("assistant".equals(role) && "output_text".equals(type));
            if (visible) {
                String value = text(block, "text");
                if (value != null && !value.isBlank()) parts.add(value);
            }
        }
        return parts.isEmpty() ? null : String.join("\n\n", parts);
    }

    private static boolean ownsTranscript(AgentSession session, Path path, ParsedTranscript parsed) {
        String clientId = session.clientSessionId();
        if (clientId == null || parsed.transcriptId() == null) return false;
        String source = normalizedSource(session.source());
        if ("claude".equals(source) && clientId.contains(":")) {
            int separator = clientId.lastIndexOf(':');
            String parentId = clientId.substring(0, separator);
            String agentId = clientId.substring(separator + 1);
            String normalizedPath = path.toString().replace('\\', '/');
            return parsed.transcriptId().equals(parentId)
                    && parsed.agentIds().contains(agentId)
                    && normalizedPath.contains("/" + parentId + "/subagents/")
                    && path.getFileName().toString().contains(agentId);
        }
        return parsed.transcriptId().equals(clientId)
                && path.getFileName().toString().contains(clientId);
    }

    private static AgentEvent toEvent(AgentSession session, ParsedMessage message) {
        return new AgentEvent(
                "tx:" + session.source() + ":" + message.sourceId(),
                session.id(),
                session.source(),
                session.clientSessionId(),
                message.turnId(),
                "TranscriptMessage",
                message.role(),
                message.text(),
                null,
                null,
                null,
                Map.of("transcript", true),
                message.observedAt());
    }

    private static Instant instant(String value) {
        if (value == null) return null;
        try {
            return Instant.parse(value);
        }
        catch (DateTimeParseException ex) {
            return null;
        }
    }

    private static String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = text(node, field);
            if (value != null) return value;
        }
        return null;
    }

    private static String text(JsonNode node, String field) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        JsonNode value = node.get(field);
        return value != null && value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
    }

    private static String normalize(String value) {
        return String.valueOf(value).replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizedSource(String value) {
        return String.valueOf(value == null ? "" : value).trim().toLowerCase(Locale.ROOT);
    }

    private record ParsedMessage(
            String sourceId,
            String turnId,
            String role,
            String text,
            Instant observedAt,
            long ordinal,
            int priority) {
    }

    private record ParsedTranscript(
            String transcriptId,
            String parentId,
            Set<String> agentIds,
            List<ParsedMessage> messages,
            int malformedLines,
            int invalidTimestamps,
            int clippedMessages) {

        boolean complete() {
            return malformedLines == 0 && invalidTimestamps == 0 && clippedMessages == 0;
        }

        String reason() {
            List<String> reasons = new ArrayList<>();
            if (malformedLines > 0) reasons.add(malformedLines + " malformed line(s) skipped");
            if (invalidTimestamps > 0) reasons.add(invalidTimestamps + " message(s) had no valid timestamp");
            if (clippedMessages > 0) reasons.add(clippedMessages + " oversized message(s) clipped");
            return reasons.isEmpty() ? null : String.join("; ", reasons);
        }
    }

    private static final class ParseIssues {
        private int malformedLines;
        private int invalidTimestamps;
        private int clippedMessages;
    }

    private record CacheStamp(String fileKey, long size, long modifiedAt, int parserVersion) {
    }

    private record CacheKey(Path path, String source) {
    }

    private record CacheEntry(CacheStamp stamp, ParsedTranscript transcript) {
    }
}
