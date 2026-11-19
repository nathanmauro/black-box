package dev.nathan.sbaagentic.project.internal.domain;

/**
 * Encodes label text for a double-quoted Mermaid flowchart label. Only a small allowlist passes
 * through; every other code point becomes a Mermaid entity code ({@code #<decimal>;}), so quotes,
 * brackets, pipes, semicolons, {@code %%} directives, backticks, angle brackets and line breaks in
 * stored text can never close the label or start a new statement.
 */
public final class MermaidText {

    private static final String PASS_THROUGH = " _.,:/-";

    private MermaidText() {}

    public static String label(String text) {
        if (text == null || text.isEmpty()) {

            return "";
        }
        StringBuilder out = new StringBuilder(text.length() + 16);
        text.codePoints().forEach(cp -> append(out, cp));

        return out.toString();
    }

    private static void append(StringBuilder out, int cp) {
        if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
            out.append(' ');

            return;
        }
        if (Character.isISOControl(cp) || Character.getType(cp) == Character.FORMAT) {

            return;
        }
        boolean asciiAlphanumeric = cp < 128 && Character.isLetterOrDigit(cp);
        if (asciiAlphanumeric || PASS_THROUGH.indexOf(cp) >= 0) {
            out.appendCodePoint(cp);

            return;
        }
        out.append('#').append(cp).append(';');
    }
}
