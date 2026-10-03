package dev.nathan.sbaagentic.project.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

class MeldCursorTest {
    @Test
    void preservesNanosecondsAndFullInstantRange() {
        assertThat(MeldCursor.parse(null)).isNull();
        for (Instant time : List.of(
                Instant.MIN,
                Instant.MAX,
                Instant.EPOCH,
                Instant.parse("2026-01-01T00:00:00.000000001Z"),
                Instant.parse("-10000-01-01T00:00:00Z"))) {
            var cursor = new MeldCursor(time, "stable-id");
            assertThat(MeldCursor.parse(cursor.encoded())).isEqualTo(cursor);
        }
    }

    @Test
    void rejectsUnsupportedAmbiguousAndUnboundedCursors() {
        String valid = new MeldCursor(Instant.EPOCH, "id").encoded();
        for (String encoded : List.of(
                "",
                "not-base64!",
                valid + "=",
                "A".repeat(1025),
                encode("v2\n1970-01-01T00:00:00Z\nid\n"),
                encode("braid-unassigned-v1\nnot-an-instant\nid\n"),
                encode("braid-unassigned-v1\n1970-01-01T00:00:00Z\n \n"),
                encode("braid-unassigned-v1\n1970-01-01T00:00:00Z\nid\tcontrol\n"),
                encode("braid-unassigned-v1\n1970-01-01T00:00:00Z\n" + "x".repeat(257) + "\n"),
                encode("braid-unassigned-v1\n1970-01-01T00:00:00Z\nid\nextra"))) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> MeldCursor.parse(encoded))
                    .withMessage("Invalid before cursor for unassigned braids.");
        }
    }

    private String encode(String value) {

        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
