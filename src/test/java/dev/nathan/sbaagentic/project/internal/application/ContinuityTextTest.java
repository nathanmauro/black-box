package dev.nathan.sbaagentic.project.internal.application;

import static org.assertj.core.api.Assertions.assertThat;

import dev.nathan.sbaagentic.recording.ExportRedactor;
import org.junit.jupiter.api.Test;

class ContinuityTextTest {

    private static final String SECRET = "Sx9Kq2Lm7Vb4Np8Rt3Wz6Hd";

    /** Masks the secret only when it is whole, as an exact-match masker does. */
    private final ExportRedactor exactMatch = text -> text.replace(SECRET, "[REDACTED]");

    @Test
    void redactsTheWholeValueBeforeClippingSoNoPrefixSurvives() {
        // Clipping first would cut the secret at 20 characters, the masker would no longer match,
        // and the first 19 characters of the secret would show.
        String text = new ContinuityText(exactMatch).clean("id " + SECRET + " tail", 20);

        assertThat(text).startsWith("id [REDACTED]").doesNotContain(SECRET.substring(0, 6));
    }

    @Test
    void flattensToOneLineWithoutControlOrFormatCharacters() {
        String text = new ContinuityText(exactMatch).clean("  one\n\ttwo\u0000three‮four  ", 100);

        // Controls separate words like whitespace; invisible format characters (a bidi override
        // here) vanish without leaving a gap.
        assertThat(text).isEqualTo("one two threefour");
    }

    @Test
    void clipsByCodePointWithAnEllipsis() {
        ContinuityText cleaner = new ContinuityText(exactMatch);

        assertThat(cleaner.clean("🚀🚀🚀🚀", 3)).isEqualTo("🚀🚀…");
        assertThat(cleaner.clean("short", 10)).isEqualTo("short");
        assertThat(cleaner.clean(null, 10)).isNull();
    }
}
