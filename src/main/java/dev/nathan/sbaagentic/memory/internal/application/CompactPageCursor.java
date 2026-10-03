package dev.nathan.sbaagentic.memory.internal.application;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import dev.nathan.sbaagentic.query.SqlInstant;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Versioned keyset position for canonical compact pages. It is bound to normalized filters and a fixed
 * cutoff; it is not a credential, because filters are re-bound from each request.
 */
final class CompactPageCursor {
    static final int MAX_LENGTH = 1024;
    static final int MAX_EVENT_ID = 512;
    private static final Pattern KEY = Pattern.compile("\\d{10}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{9}");
    private static final Set<String> FIELDS = new TreeSet<>(List.of("v", "f", "u", "k", "i"));
    private static final JsonMapper STRICT = JsonMapper.builder()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    record Position(String fingerprint, Instant until, String orderKey, String eventId) {}

    private CompactPageCursor() {}

    static String fingerprint(List<String> terms, String projectExact, String sessionId, Instant until) {
        byte[] canonical = CompactSearchJson.write(Arrays.asList(terms, projectExact, sessionId, until.toString()))
                .getBytes(StandardCharsets.UTF_8);
        try {

            return Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    /** Throws {@link IllegalStateException} when a stored row cannot be represented as a valid position. */
    static String encode(Position position) {
        if (!validKey(position.orderKey(), position.until()) || !validEventId(position.eventId())) {
            throw new IllegalStateException("The last delivered event has no representable cursor position.");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("v", 1);
        body.put("f", position.fingerprint());
        body.put("u", position.until().toString());
        body.put("k", position.orderKey());
        body.put("i", position.eventId());

        String encoded = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(CompactSearchJson.write(body).getBytes(StandardCharsets.UTF_8));
        if (encoded.length() > MAX_LENGTH) {
            throw new IllegalStateException("The last delivered event has no representable cursor position.");
        }

        return encoded;
    }

    /** Throws {@link IllegalArgumentException} with a client-facing reason for any malformed cursor. */
    static Position decode(String cursor) {
        if (cursor == null || cursor.isEmpty() || cursor.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "before must be a nonempty cursor of at most " + MAX_LENGTH + " characters.");
        }
        byte[] raw;
        try {
            raw = Base64.getUrlDecoder().decode(cursor);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("before is not a valid cursor.");
        }
        if (!Base64.getUrlEncoder().withoutPadding().encodeToString(raw).equals(cursor)) {
            throw new IllegalArgumentException("before is not a valid cursor.");
        }
        JsonNode node;
        try {
            // REPORT, not replacement: a malformed byte must never become U+FFFD inside a valid-looking ID.
            node = STRICT.readTree(StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(raw))
                    .toString());
        } catch (JsonProcessingException | CharacterCodingException ex) {
            throw new IllegalArgumentException("before is not a valid cursor.");
        }
        if (node == null || !node.isObject()) throw new IllegalArgumentException("before is not a valid cursor.");
        Set<String> names = new TreeSet<>();
        node.fieldNames().forEachRemaining(names::add);
        if (!names.equals(FIELDS)) throw new IllegalArgumentException("before is not a valid cursor.");
        if (!node.get("v").isInt() || node.get("v").intValue() != 1) {
            throw new IllegalArgumentException("before uses an unsupported cursor version.");
        }
        for (String field : List.of("f", "u", "k", "i")) {
            if (!node.get(field).isTextual()) throw new IllegalArgumentException("before is not a valid cursor.");
        }
        Instant until;
        try {
            until = Instant.parse(node.get("u").textValue());
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException("before has an invalid cutoff.");
        }
        if (!until.toString().equals(node.get("u").textValue())) {
            throw new IllegalArgumentException("before has an invalid cutoff.");
        }
        String key = node.get("k").textValue();
        if (!validKey(key, until)) throw new IllegalArgumentException("before has an invalid position timestamp.");
        String id = node.get("i").textValue();
        if (!validEventId(id)) throw new IllegalArgumentException("before has an invalid position event ID.");

        return new Position(node.get("f").textValue(), until, key, id);
    }

    /**
     * A key must round-trip through a real instant and may not lie after the cutoff. The canonical ISO text is
     * rebuilt directly because {@code LocalDateTime} cannot represent the Instant.MIN/MAX years that keys cover.
     */
    private static boolean validKey(String key, Instant until) {
        if (key == null || !KEY.matcher(key).matches())

            return false;

        long year = Long.parseLong(key.substring(0, 10)) - 1_000_000_000L;
        String digits = String.format(Locale.ROOT, "%04d", Math.abs(year));
        String text = (year < 0 ? "-" : year > 9999 ? "+" : "") + digits + key.substring(10) + "Z";
        try {
            Instant instant = Instant.parse(text);

            return SqlInstant.key(instant).equals(key) && !instant.isAfter(until);
        } catch (DateTimeParseException ex) {

            return false;
        }
    }

    private static boolean validEventId(String id) {

        return id != null
                && !id.isEmpty()
                && id.length() <= MAX_EVENT_ID
                && id.chars().noneMatch(Character::isISOControl)
                && wellFormed(id);
    }

    static boolean wellFormed(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(i + 1)))

                    return false;

                i++;
            } else if (Character.isLowSurrogate(c))

                return false;
        }

        return true;
    }
}
