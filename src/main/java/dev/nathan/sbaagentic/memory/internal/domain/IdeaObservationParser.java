package dev.nathan.sbaagentic.memory.internal.domain;

import dev.nathan.sbaagentic.recording.Ideas;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Best-effort parser for the {@code [Idea]} observations agents wrote before the {@code Idea} kind
 * existed. The body is a bullet list ({@code - origin: …}, {@code - What: …}, {@code - legs: N},
 * {@code - connects: …}, {@code - status: …}); unknown or missing values become warnings, never
 * failures, so every candidate can be reviewed in a dry run.
 */
public final class IdeaObservationParser {

    private static final Pattern BULLET =
            Pattern.compile("^[ \\t]*[-*][ \\t]*([A-Za-z][A-Za-z ]*?)[ \\t]*:[ \\t]*(.*)$", Pattern.MULTILINE);
    private static final Pattern VERBATIM = Pattern.compile("Verbatim:\\s*[\"“](.+)[\"”]");
    private static final Pattern FIRST_INT = Pattern.compile("^\\s*(-?\\d+)");
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?])\\s");

    private IdeaObservationParser() {}

    public static boolean isIdeaObservation(String text) {

        return text != null && text.startsWith(Ideas.TEXT_PREFIX);
    }

    public static ParsedIdea parse(String text) {
        List<String> warnings = new ArrayList<>();
        String body = text == null ? "" : text;
        String firstLine = body.lines().findFirst().orElse("");
        String title = firstLine.startsWith(Ideas.TEXT_PREFIX)
                ? firstLine.substring(Ideas.TEXT_PREFIX.length()).strip()
                : firstLine.strip();
        if (title.isEmpty()) {
            warnings.add("missing title: the first line after [Idea] is empty");
            title = null;
        }

        String originLine = bullet(body, "origin");
        String what = bullet(body, "what");
        String legsLine = bullet(body, "legs");
        String connectsLine = bullet(body, "connects");
        String statusLine = bullet(body, "status");

        String origin = null;
        if (originLine == null) {
            warnings.add("missing origin: defaulted to " + Ideas.ORIGIN_AGENT_PROPOSED);
            origin = Ideas.ORIGIN_AGENT_PROPOSED;
        } else {
            String token = firstWord(originLine);
            origin = Ideas.normalizeOrigin(token);
            if (origin == null) {
                warnings.add("unknown origin '" + token + "': defaulted to " + Ideas.ORIGIN_AGENT_PROPOSED);
                origin = Ideas.ORIGIN_AGENT_PROPOSED;
            }
        }

        String quote = null;
        Matcher verbatim = VERBATIM.matcher(body);
        if (verbatim.find()) {
            quote = verbatim.group(1).strip();
        } else {
            warnings.add("missing quote: no Verbatim: \"…\" found");
        }

        String oneLiner = null;
        if (what == null || what.isBlank()) {
            warnings.add("missing What: one-liner falls back to the title");
        } else {
            oneLiner = firstSentence(what);
        }
        if (oneLiner == null) {
            oneLiner = title;
        }

        Integer legs = null;
        if (legsLine == null) {
            warnings.add("missing legs");
        } else {
            Matcher number = FIRST_INT.matcher(legsLine);
            if (number.find()) {
                Integer parsed = parseIntOrNull(number.group(1));
                if (parsed == null || parsed < Ideas.MIN_LEGS || parsed > Ideas.MAX_LEGS) {
                    warnings.add("legs " + number.group(1) + " is outside " + Ideas.MIN_LEGS + ".." + Ideas.MAX_LEGS
                            + ": dropped");
                } else {
                    legs = parsed;
                }
            } else {
                warnings.add("unreadable legs '" + legsLine.strip() + "': dropped");
            }
        }

        String status;
        if (statusLine == null) {
            warnings.add("missing status: defaulted to " + Ideas.STATUS_UNTOUCHED);
            status = Ideas.STATUS_UNTOUCHED;
        } else {
            String token = firstWord(statusLine);
            status = Ideas.normalizeStatus(token);
            if (status == null) {
                warnings.add("unknown status '" + token + "': defaulted to " + Ideas.STATUS_UNTOUCHED);
                status = Ideas.STATUS_UNTOUCHED;
            }
        }

        List<String> connects = List.of();
        if (connectsLine == null) {
            warnings.add("missing connects");
        } else {
            connects = splitOutsideParentheses(connectsLine);
        }

        return new ParsedIdea(title, oneLiner, origin, quote, legs, status, connects, List.copyOf(warnings));
    }

    /** Splits on commas that are not inside parentheses; blank parts are dropped. */
    public static List<String> splitOutsideParentheses(String value) {
        List<String> parts = new ArrayList<>();
        if (value == null) {

            return parts;
        }
        StringBuilder current = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')' && depth > 0) {
                depth--;
            }
            if (c == ',' && depth == 0) {
                addPart(parts, current);
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        addPart(parts, current);

        return parts;
    }

    private static void addPart(List<String> parts, StringBuilder current) {
        String part = current.toString().strip();
        if (!part.isEmpty()) {
            parts.add(part);
        }
    }

    /** {@code null} when the digits overflow an int; one malformed value must not abort a dry run. */
    private static Integer parseIntOrNull(String digits) {
        try {

            return Integer.parseInt(digits);
        } catch (NumberFormatException overflow) {

            return null;
        }
    }

    private static String bullet(String body, String label) {
        Matcher matcher = BULLET.matcher(body);
        while (matcher.find()) {
            if (matcher.group(1).strip().toLowerCase(Locale.ROOT).equals(label)) {

                return matcher.group(2);
            }
        }

        return null;
    }

    private static String firstWord(String value) {
        String stripped = value.strip();
        int end = 0;
        while (end < stripped.length() && !Character.isWhitespace(stripped.charAt(end))) {
            end++;
        }

        return stripped.substring(0, end).replaceAll("^[\\p{Punct}&&[^-]]+|[\\p{Punct}&&[^-]]+$", "");
    }

    private static String firstSentence(String value) {
        String stripped = value.strip();
        if (stripped.isEmpty()) {

            return null;
        }
        String[] sentences = SENTENCE_END.split(stripped, 2);

        return sentences[0].strip();
    }

    public record ParsedIdea(
            String title,
            String oneLiner,
            String origin,
            String quote,
            Integer legs,
            String status,
            List<String> connects,
            List<String> warnings) {}
}
