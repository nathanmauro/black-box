package dev.nathan.sbaagentic.memory.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.nathan.sbaagentic.query.SqlInstant;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

class CompactPageCursorTest {

    @Test
    void positionsThatExceedTheEncodedCursorLimitAreRejected() {
        Instant until = Instant.parse("2026-10-03T00:00:00Z");
        for (String eventId : List.of("界".repeat(256), "\\".repeat(512))) {
            var position = new CompactPageCursor.Position(
                    CompactPageCursor.fingerprint(List.of("needle"), null, null, until),
                    until,
                    SqlInstant.key(until),
                    eventId);
            assertThatThrownBy(() -> CompactPageCursor.encode(position))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("representable cursor position");
        }
    }

    @Test
    void positionsRoundTripAcrossTheEntireSupportedInstantRange() {
        for (Instant instant : List.of(
                Instant.MIN,
                Instant.parse("-0001-12-31T23:59:59.999999999Z"),
                Instant.EPOCH,
                Instant.parse("2026-10-03T00:00:00.000000001Z"),
                Instant.parse("+10000-01-01T00:00:00Z"),
                Instant.MAX)) {
            var position = new CompactPageCursor.Position("f", Instant.MAX, SqlInstant.key(instant), "event-1");
            assertThat(CompactPageCursor.decode(CompactPageCursor.encode(position)))
                    .as(instant.toString())
                    .isEqualTo(position);
            var atCutoff = new CompactPageCursor.Position("f", instant, SqlInstant.key(instant), "event-1");
            assertThat(CompactPageCursor.decode(CompactPageCursor.encode(atCutoff)))
                    .as(instant.toString())
                    .isEqualTo(atCutoff);
        }
    }

    @Test
    void keysOutsideRealInstantsOrAfterTheCutoffAreRejected() {
        Instant until = Instant.parse("2026-10-03T00:00:00Z");
        for (String key : List.of(
                "1000002026-02-30T00:00:00.000000000",
                "1000002026-10-03T00:00:00.000000001",
                "1000002026-02-29T00:00:00.000000000",
                "2000000001-01-01T00:00:00.000000000",
                "1000002026-13-01T00:00:00.000000000",
                "1000002026-10-02T24:00:00.000000000")) {
            assertThatThrownBy(() -> CompactPageCursor.encode(new CompactPageCursor.Position("f", until, key, "e")))
                    .as(key)
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void invalidUtf8IsRejectedRatherThanReplaced() throws Exception {
        String valid = CompactPageCursor.encode(new CompactPageCursor.Position(
                "f", Instant.parse("2026-10-03T00:00:00Z"), "1000002026-10-02T00:00:00.000000000", "event-1"));
        String json = new String(Base64.getUrlDecoder().decode(valid), StandardCharsets.UTF_8);
        int at = json.indexOf("event-1") + "event".length();
        var raw = new ByteArrayOutputStream();
        raw.write(json.substring(0, at).getBytes(StandardCharsets.UTF_8));
        raw.write(0xff);
        raw.write(json.substring(at).getBytes(StandardCharsets.UTF_8));
        String tampered = Base64.getUrlEncoder().withoutPadding().encodeToString(raw.toByteArray());
        assertThatThrownBy(() -> CompactPageCursor.decode(tampered))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a valid cursor");

        String surrogate = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(json.replace("event-1", "event\\ud800").getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> CompactPageCursor.decode(surrogate)).isInstanceOf(IllegalArgumentException.class);
    }
}
