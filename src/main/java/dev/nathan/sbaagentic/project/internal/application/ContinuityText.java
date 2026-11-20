package dev.nathan.sbaagentic.project.internal.application;

import dev.nathan.sbaagentic.recording.ExportRedactor;

/**
 * The only way stored text leaves the continuity endpoints. Redaction runs on the whole stored
 * value first and clipping second: clipping first could cut a secret so its pattern no longer
 * matches while a prefix of it still shows.
 */
final class ContinuityText {

    static final int ID_CHARS = 200;
    static final int PATH_CHARS = 300;
    static final int LABEL_CHARS = 80;
    static final int EXCERPT_CHARS = 160;

    private final ExportRedactor redactor;

    ContinuityText(ExportRedactor redactor) {
        this.redactor = redactor;
    }

    /** Redacted, flattened to one line, without control or format characters, clipped. */
    String clean(String raw, int maxCodePoints) {
        if (raw == null) {

            return null;
        }
        String redacted = redactor.redactForExport(raw);
        String flat = flatten(redacted);

        return clip(flat, maxCodePoints);
    }

    private static String flatten(String text) {
        StringBuilder out = new StringBuilder(text.length());
        boolean pendingSpace = false;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            boolean space = Character.isWhitespace(cp) || Character.isSpaceChar(cp) || Character.isISOControl(cp);
            if (space) {
                pendingSpace = out.length() > 0;
                continue;
            }
            if (Character.getType(cp) == Character.FORMAT) {
                continue;
            }
            if (pendingSpace) {
                out.append(' ');
                pendingSpace = false;
            }
            out.appendCodePoint(cp);
        }

        return out.toString();
    }

    private static String clip(String text, int maxCodePoints) {
        if (text.codePointCount(0, text.length()) <= maxCodePoints) {

            return text;
        }
        int end = text.offsetByCodePoints(0, Math.max(0, maxCodePoints - 1));

        return text.substring(0, end).stripTrailing() + "…";
    }
}
