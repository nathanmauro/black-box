package dev.nathan.sbaagentic.memory.internal.domain;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure text rules for continuation prompts. The no-action filter, the dynamic fence and the id
 * hash are ported from {@code scripts/linear/blackbox_linear.py}; the token rule is the trajectory
 * view's, so the API and the Projects graph agree on what counts as the same item.
 */
public final class ContinuationText {

    public static final String CLIPPED = "[clipped]";
    public static final String ID_PREFIX = "bbc1-";

    /** Same alternatives as {@code NO_ACTION} in {@code blackbox_linear.py}, applied at the start of the text. */
    private static final Pattern NO_ACTION = Pattern.compile(
            "^(?:none|n/?a|no (?:further )?(?:action|open loops?)|nothing|done|completed)(?:$|[.\\s—–:-])",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS);

    private static final Pattern BACKTICK_RUN = Pattern.compile("`+");
    private static final Pattern WORD_SEPARATOR = Pattern.compile("\\s+");
    private static final Pattern EDGE_PUNCTUATION = Pattern.compile("^[^\\p{L}\\p{N}]+|[^\\p{L}\\p{N}]+$");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC);

    private ContinuationText() {}

    /** Python {@code " ".join(text.split())}: whitespace runs become one space; control characters are dropped. */
    public static String collapse(String raw) {
        if (raw == null) {

            return "";
        }
        StringBuilder out = new StringBuilder(raw.length());
        boolean pendingSpace = false;
        for (int index = 0; index < raw.length(); ) {
            int codePoint = raw.codePointAt(index);
            index += Character.charCount(codePoint);
            if (isPythonWhitespace(codePoint)) {
                pendingSpace = out.length() > 0;
                continue;
            }
            if (Character.getType(codePoint) == Character.CONTROL) {
                continue;
            }
            if (pendingSpace) {
                out.append(' ');
                pendingSpace = false;
            }
            out.appendCodePoint(codePoint);
        }

        return out.toString();
    }

    /** True when collapsed text says there is nothing to do ("none", "n/a", "done", ...). */
    public static boolean noAction(String collapsed) {

        return collapsed != null && NO_ACTION.matcher(collapsed).lookingAt();
    }

    /** Collapsed and case-folded, matching {@code normalized()} in {@code blackbox_linear.py}. */
    public static String normalized(String text) {

        return caseFold(collapse(text));
    }

    /** {@code bbc1-} plus 24 hex characters of SHA-256 over the JSON array {@code [root, normalizedText]}. */
    public static String candidateId(String root, String text) {
        byte[] json = jsonPair(root == null ? "" : root, normalized(text)).getBytes(StandardCharsets.UTF_8);
        try {

            return ID_PREFIX
                    + HexFormat.of()
                            .formatHex(MessageDigest.getInstance("SHA-256").digest(json))
                            .substring(0, 24);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    /** Python {@code json.dumps([first, second], ensure_ascii=True)}, byte for byte. */
    static String jsonPair(String first, String second) {

        return "[" + jsonString(first) + ", " + jsonString(second) + "]";
    }

    /**
     * Token Jaccard similarity over lower-case words. Each whitespace-delimited word is one token with
     * its edge punctuation removed, so a shared identifier, ticket number or file path counts once
     * instead of splitting into many shared fragments that would merge different actions.
     */
    public static double jaccard(String left, String right) {
        Set<String> leftTokens = tokens(left);
        Set<String> rightTokens = tokens(right);
        if (leftTokens.isEmpty() || rightTokens.isEmpty()) {

            return 0;
        }
        int intersection = 0;
        for (String token : leftTokens) {
            if (rightTokens.contains(token)) {
                intersection++;
            }
        }
        Set<String> union = new HashSet<>(leftTokens);
        union.addAll(rightTokens);

        return union.isEmpty() ? 0 : (double) intersection / union.size();
    }

    static Set<String> tokens(String value) {
        Set<String> tokens = new LinkedHashSet<>();
        if (value == null) {

            return tokens;
        }
        for (String word : WORD_SEPARATOR.split(value.toLowerCase(Locale.ROOT))) {
            String token = EDGE_PUNCTUATION.matcher(word).replaceAll("");
            if (!token.isEmpty()) {
                tokens.add(token);
            }
        }

        return tokens;
    }

    /** Fence recorded text so Markdown, headings and instructions inside it stay quoted source material. */
    public static String literal(String text) {
        String value = text == null ? "" : text;
        int longest = 0;
        Matcher run = BACKTICK_RUN.matcher(value);
        while (run.find()) {
            longest = Math.max(longest, run.end() - run.start());
        }
        String fence = "`".repeat(Math.max(3, longest + 1));

        return fence + "text\n" + value + "\n" + fence;
    }

    /** Multi-line recorded text for a fence: normalized line endings, no control characters, trimmed. */
    public static String sourceText(String raw) {
        if (raw == null) {

            return "";
        }
        String text = raw.replace("\r\n", "\n").replace('\r', '\n');
        StringBuilder out = new StringBuilder(text.length());
        for (int index = 0; index < text.length(); ) {
            int codePoint = text.codePointAt(index);
            index += Character.charCount(codePoint);
            if (codePoint == '\n' || codePoint == '\t' || Character.getType(codePoint) != Character.CONTROL) {
                out.appendCodePoint(codePoint);
            }
        }

        return out.toString().strip();
    }

    /** At most {@code maxChars} UTF-16 units, never ending between the halves of a surrogate pair. */
    public static String clip(String text, int maxChars) {
        if (text == null) {

            return "";
        }
        if (text.length() <= maxChars) {

            return text;
        }
        int end = Math.max(0, maxChars);
        if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }

        return text.substring(0, end);
    }

    /** The Recall evidence link shape: the owning session at the exact event, with Activity scope cleared. */
    public static String href(String sessionId, String eventId) {

        return "/?view=browse&session="
                + URLEncoder.encode(sessionId == null ? "" : sessionId, StandardCharsets.UTF_8)
                + "&event="
                + URLEncoder.encode(eventId == null ? "" : eventId, StandardCharsets.UTF_8)
                + "&project=";
    }

    public static String day(Instant instant) {

        return instant == null ? "an unknown date" : DAY.format(instant);
    }

    /** Whole days between a recorded time and now, never negative. */
    public static int ageDays(Instant recordedAt, Instant now) {
        if (recordedAt == null || now == null || !recordedAt.isBefore(now)) {

            return 0;
        }

        return (int)
                Math.min(Integer.MAX_VALUE, Duration.between(recordedAt, now).toDays());
    }

    public static String ago(int days) {
        if (days <= 0) {

            return "less than a day ago";
        }

        return days == 1 ? "1 day ago" : days + " days ago";
    }

    private static String caseFold(String text) {

        return text.toUpperCase(Locale.ROOT).toLowerCase(Locale.ROOT).replace('ς', 'σ');
    }

    private static String jsonString(String value) {
        StringBuilder out = new StringBuilder(value.length() + 2).append('"');
        for (int index = 0; index < value.length(); index++) {
            char c = value.charAt(index);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20 || c > 0x7e) {
                        out.append("\\u").append(HexFormat.of().toHexDigits(c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }

        return out.append('"').toString();
    }

    /** Python {@code str.isspace()}: Unicode category Zs or bidirectional class WS, B or S. */
    private static boolean isPythonWhitespace(int codePoint) {

        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint) || codePoint == 0x85;
    }
}
