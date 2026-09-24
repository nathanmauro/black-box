package dev.nathan.sbaagentic.judgment.internal.adapter.out.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nathan.sbaagentic.judgment.internal.application.Judgment;
import dev.nathan.sbaagentic.judgment.internal.domain.BeatState;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import org.slf4j.LoggerFactory;

/** Explicit opt-in content telemetry. The sink must be approved for private request content. */
public final class JevTelemetry {
    static final int MAX_BODY_CHARS = 32_768;
    private final boolean enabled;
    private final Consumer<Map<String, Object>> sink;

    public JevTelemetry(boolean enabled, ObjectMapper mapper) {
        this(enabled, fields -> {
            try {
                LoggerFactory.getLogger(JevTelemetry.class).info("{}", mapper.writeValueAsString(fields));
            } catch (Exception ignored) {
                /* Telemetry must not affect classification. */
            }
        });
    }

    JevTelemetry(boolean enabled, Consumer<Map<String, Object>> sink) {
        this.enabled = enabled;
        this.sink = sink;
    }

    static JevTelemetry noop() {

        return new JevTelemetry(false, fields -> {});
    }

    void requested(String requestId, BeatState state, Instant at, String body, String credential) {
        if (!enabled)

            return;
        try {
            Map<String, Object> fields = fields("blackbox.jev.requested", requestId, state, at);
            boolean omitted = credential != null && !credential.isBlank() && body.contains(credential);
            fields.put("request_body_chars", body.length());
            fields.put(
                    "request_body_sha256",
                    HexFormat.of()
                            .formatHex(MessageDigest.getInstance("SHA-256")
                                    .digest(body.getBytes(StandardCharsets.UTF_8))));
            fields.put("request_body_omitted", omitted);
            fields.put("request_body_truncated", !omitted && body.length() > MAX_BODY_CHARS);
            // This is the same serialized string passed to the HTTP transport, not a reconstruction.
            if (!omitted) fields.put("request_body", body.substring(0, Math.min(body.length(), MAX_BODY_CHARS)));
            emit(fields);
        } catch (Exception ignored) {
        }
    }

    void completed(String requestId, BeatState state, Instant at, long durationMs, Judgment judgment, Exception error) {
        if (!enabled)

            return;
        Map<String, Object> fields = fields("blackbox.jev.completed", requestId, state, at);
        fields.put("duration_ms", durationMs);
        fields.put("outcome", error == null ? "success" : "error");
        fields.put("error_category", error == null ? "none" : error.getClass().getSimpleName());
        if (judgment != null) {
            fields.put("phase", judgment.phase());
            fields.put("salience", judgment.salience());
            fields.put("novelty", judgment.novelty());
            fields.put("human", judgment.human());
            fields.put("kin", judgment.kin());
            fields.put(
                    "model",
                    judgment.model() != null && judgment.model().matches("jev-[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
                            ? judgment.model()
                            : "unknown");
            fields.put("version", judgment.version());
        }
        emit(fields);
    }

    private Map<String, Object> fields(String event, String requestId, BeatState state, Instant at) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("event", event);
        fields.put("schema_version", 1);
        fields.put("occurred_at", at.toString());
        fields.put("request_id", requestId);
        fields.put("session_id", state.beat().sessionId());
        fields.put("beat_id", state.beat().id());
        fields.put("event_ids", state.beat().events().stream().map(e -> e.id()).toList());
        String source = state.session().source();
        fields.put(
                "source",
                source != null
                                && java.util.Set.of("codex", "claude", "manual", "constellate")
                                        .contains(source)
                        ? source
                        : "other");

        return fields;
    }

    private void emit(Map<String, Object> fields) {
        try {
            sink.accept(fields);
        } catch (RuntimeException ignored) {
        }
    }
}
