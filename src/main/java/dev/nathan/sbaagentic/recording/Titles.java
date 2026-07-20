package dev.nathan.sbaagentic.recording;

/**
 * Single source of truth for how session titles are normalized: collapse
 * whitespace, trim, and cap length. Shared by ingest-time derivation and
 * AI-generated titles so every stored title follows the same shape.
 */
public final class Titles {

    public static final int MAX_LENGTH = 96;

    private Titles() {
    }

    /** Collapse whitespace, trim, and cap to {@link #MAX_LENGTH}. Blank input yields a stable default. */
    public static String sanitize(String value) {
        String compact = (value == null ? "" : value).replaceAll("\\s+", " ").trim();
        if (compact.isEmpty()) {
            return "Untitled session";
        }
        return compact.length() <= MAX_LENGTH ? compact : compact.substring(0, MAX_LENGTH - 3) + "...";
    }

    /** The first line of {@code value}, or {@code null} when {@code value} is {@code null}. */
    public static String firstLine(String value) {
        if (value == null) {
            return null;
        }
        int newline = value.indexOf('\n');
        return newline >= 0 ? value.substring(0, newline) : value;
    }
}
