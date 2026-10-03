package dev.nathan.sbaagentic.project.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class BraidDiscoveryCursorTest {
    @Test
    void roundTripsPreciseAndExtendedTimesWithExactIdentityAndFilterBinding() {
        String fingerprint = BraidDiscoveryCursor.fingerprint("文😀%_", "exact session");
        for (Instant time :
                List.of(Instant.MIN, Instant.MAX, Instant.EPOCH, Instant.parse("2026-01-01T00:00:00.000000001Z"))) {
            var cursor = new BraidDiscoveryCursor(time, "legacy id/😀\nline", fingerprint);
            assertThat(BraidDiscoveryCursor.parse(cursor.encoded(), fingerprint))
                    .isEqualTo(cursor);
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> BraidDiscoveryCursor.parse(
                            cursor.encoded(), BraidDiscoveryCursor.fingerprint("文😀%_", "other")));
        }
        assertThat(BraidDiscoveryCursor.fingerprint(null, "ab"))
                .isNotEqualTo(BraidDiscoveryCursor.fingerprint("a", "b"));
        assertThat(BraidDiscoveryCursor.fingerprint(null, null))
                .isNotEqualTo(BraidDiscoveryCursor.fingerprint("", null));
    }

    @Test
    void rejectsMalformedNoncanonicalAndOtherCoverageCursors() {
        String fingerprint = BraidDiscoveryCursor.fingerprint(null, null);
        String valid = new BraidDiscoveryCursor(Instant.EPOCH, "id", fingerprint).encoded();
        assertThat(BraidDiscoveryCursor.parse(null, fingerprint)).isNull();
        for (String bad : List.of(
                "",
                "invalid!",
                valid + "=",
                "A".repeat(131073),
                new MeldCursor(Instant.EPOCH, "id").encoded(),
                new BraidDiscoveryCursor(Instant.EPOCH, "a\u0000b", fingerprint).encoded()))
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> BraidDiscoveryCursor.parse(bad, fingerprint))
                    .withMessageContaining("Invalid before cursor");
    }
}
