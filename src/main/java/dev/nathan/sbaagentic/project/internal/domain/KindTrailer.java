package dev.nathan.sbaagentic.project.internal.domain;

import java.util.regex.Pattern;

/**
 * The decision-capture convention ends a rationale with a {@code Kind: <kind> | ...} trailer line.
 * Nothing parses it at write time, so the continuity linter checks for it after the fact.
 */
public final class KindTrailer {

    private static final Pattern TRAILER = Pattern.compile("(?im)^[ \\t]*kind:[ \\t]*[^\\s|]");

    private KindTrailer() {}

    /** True when any of the texts has a line that starts with a non-empty {@code Kind:} field. */
    public static boolean presentIn(String... texts) {
        for (String text : texts) {
            if (text != null && TRAILER.matcher(text).find()) {

                return true;
            }
        }

        return false;
    }
}
