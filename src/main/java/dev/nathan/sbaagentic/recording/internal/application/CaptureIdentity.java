package dev.nathan.sbaagentic.recording.internal.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.nathan.sbaagentic.recording.IdempotentEventIngestRequest;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** Stable namespace and digest before content normalization, redaction, or server defaults. */
record CaptureIdentity(String source, String clientSessionId, String captureId, String requestHash) {
    private static final ObjectMapper JSON = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    static CaptureIdentity from(IdempotentEventIngestRequest request) {
        if (request == null || request.event() == null) {
            throw new IllegalArgumentException("An event body is required.");
        }
        String id = request.captureId();
        if (id == null || !id.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
            throw new IllegalArgumentException("captureId must be a UUID in canonical hyphenated form.");
        }
        var event = request.event();
        if (event.source() == null
                || event.source().isBlank()
                || event.clientSessionId() == null
                || event.clientSessionId().isBlank()
                || event.eventType() == null
                || event.eventType().isBlank()) {
            throw new IllegalArgumentException("Event source, clientSessionId, and eventType are required.");
        }
        try {
            String source = event.source().trim().toLowerCase(Locale.ROOT);
            String clientSessionId = event.clientSessionId().trim();
            DigestV1 fingerprinted = new DigestV1(
                    source,
                    clientSessionId,
                    event.turnId(),
                    event.eventType(),
                    event.role(),
                    event.text(),
                    event.cwd(),
                    event.toolName(),
                    event.toolInput(),
                    event.toolOutput(),
                    event.metadata(),
                    event.observedAt());
            byte[] canonical = JSON.writeValueAsBytes(sorted(JSON.valueToTree(fingerprinted)));
            String hash = HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));

            return new CaptureIdentity(
                    source, clientSessionId, UUID.fromString(id).toString(), hash);
        } catch (JsonProcessingException | NoSuchAlgorithmException ex) {
            throw new IllegalStateException("Unable to fingerprint event payload.", ex);
        }
    }

    // Freeze this field contract independently of the wire DTO. Adding an optional request field
    // must not change existing receipts or invalidate queued captures from an older client.
    private record DigestV1(
            String source,
            String clientSessionId,
            String turnId,
            String eventType,
            String role,
            String text,
            String cwd,
            String toolName,
            Object toolInput,
            Object toolOutput,
            Map<String, Object> metadata,
            Instant observedAt) {}

    private static JsonNode sorted(JsonNode value) {
        if (value.isObject()) {
            TreeMap<String, JsonNode> fields = new TreeMap<>();
            value.fields().forEachRemaining(field -> fields.put(field.getKey(), sorted(field.getValue())));
            ObjectNode result = JSON.createObjectNode();
            fields.forEach(result::set);

            return result;
        }
        if (value.isArray()) {
            ArrayNode result = JSON.createArrayNode();
            value.forEach(item -> result.add(sorted(item)));

            return result;
        }

        return value;
    }
}
