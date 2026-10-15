package dev.nathan.sbaagentic.recording.internal.application;

import dev.nathan.sbaagentic.recording.ExportRedactor;
import dev.nathan.sbaagentic.recording.IngestionProperties;
import dev.nathan.sbaagentic.recording.IngestionRedactor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class RedactionService implements ExportRedactor, IngestionRedactor {

    private static final String REDACTED = "[REDACTED]";
    // Anchored at token starts via lookbehind, with a bounded lazy prefix and a possessive
    // suffix: greedy runs flanking the keyword alternation backtrack quadratically on long
    // keyword-dense inputs (measured seconds of CPU per event), this shape stays linear.
    private static final String SECRET_KEY =
            "(?<![A-Za-z0-9_-])[A-Za-z0-9_-]{0,40}?(?:api[_-]?key|secret[_-]?access[_-]?key|secret|token|passwd|password|client[_-]?secret|access[_-]?token|authorization)[A-Za-z0-9_-]*+";
    // Clip oversized unredacted input before applying any rules. Anything past this
    // scan budget is dropped rather than stored unscanned; replacements may expand it.
    private static final int MAX_SCAN_CHARS = 50_000;
    private static final String CLIP_MARKER = " …[truncated]";
    // Bare, quoted, or JSON-escaped quoted keys (\" through three encoding levels) using the
    // existing default assignment-name policy. "=>" joins plutil -p, launchctl print, Ruby and
    // PHP pairs. Each run is bounded, so a near miss costs constant work per start position.
    private static final Pattern ASSIGNMENT =
            Pattern.compile("(?i)(\\\\{1,7}\"|[\"']|)(" + SECRET_KEY + ")\\1(\\s*(?:=>|[=:])\\s*)");
    // A property-list member: <key>NAME</key>, then its <string> or <data> value after raw or
    // JSON-escaped whitespace, optionally behind cat -n, grep -n or Read line-number gutters.
    private static final Pattern PLIST_ENTRY = Pattern.compile(
            "<key>([^<]{1,128})</key>(?:\\s|\\\\{1,7}[nrt]|[0-9]{1,9}(?:→|[:\\t-]|\\\\{1,7}t)){0,256}+<(string|data)>");
    // Provider keys shaped apikey_<hex>[_<hex>...], also directly after a JSON-escaped newline or
    // tab. Possessive runs keep this linear on long hexadecimal near misses.
    private static final Pattern HEX_API_KEY = Pattern.compile(
            "(?:(?<![A-Za-z0-9_])|(?<=\\\\[nrt]))apikey_[0-9a-fA-F]++(?:_[0-9a-fA-F]++)*+(?![A-Za-z0-9])");
    // Fewer hexadecimal digits than this is an identifier such as apikey_cafe, not a credential.
    private static final int MIN_API_KEY_HEX_DIGITS = 16;
    // A token count, never a credential, has at most this many digits.
    private static final int MAX_TOKEN_COUNT_DIGITS = 12;

    private final List<RedactionRule> exportRules = exportRules();

    private static List<RedactionRule> exportRules() {
        List<RedactionRule> result = new ArrayList<>(credentialRules());
        // JSON embedded in a text leaf may be escaped more than once. Conservatively
        // remove the rest of that leaf after a quoted credential key; do not guess
        // nested string boundaries and accidentally retain part of a secret.
        result.add(literal(SECRET_KEY + "\\\\*[\"']\\s*:\\s*.*", Pattern.CASE_INSENSITIVE | Pattern.DOTALL));
        // Last, so a quoted apikey_<hex> member name still reaches the rule above.
        result.add(hexApiKeyRule());

        return result;
    }

    @Override
    public String redactForExport(String text) {
        if (text == null)

            return null;

        return redactDefaults(text, exportRules);
    }

    // Match the durable hook's conservative, separator-insensitive secret-key policy.
    private static final List<String> SECRET_KEY_WORDS =
            List.of("apikey", "secret", "token", "passwd", "password", "authorization", "credential", "privatekey");

    private final boolean enabled;
    private final boolean defaultRules;
    private final List<RedactionRule> rules;

    public RedactionService(IngestionProperties properties) {
        this.enabled = properties.isRedactEnabled();
        List<String> customPatterns = properties.getRedactPatterns();
        this.defaultRules = customPatterns.isEmpty();
        this.rules = defaultRules ? builtInRules() : customRules(customPatterns);
    }

    @Override
    public String redact(String text) {
        if (!enabled || text == null) {

            return text;
        }
        if (defaultRules) {

            return redactDefaults(text, rules);
        }
        String redacted = clipScalar(text);
        for (RedactionRule rule : rules) {
            redacted = rule.redact(redacted);
        }

        return redacted;
    }

    private static String redactDefaults(String text, List<RedactionRule> rules) {
        String clipped = clipScalar(text);
        boolean truncated = text.length() > MAX_SCAN_CHARS;
        // The marker is not part of the value. An unclosed quoted credential at the scan
        // boundary must consume the retained value without swallowing that marker.
        String result = truncated ? clipped.substring(0, clipped.length() - CLIP_MARKER.length()) : clipped;
        for (RedactionRule rule : rules) {
            result = rule.redact(result);
        }

        return result + (truncated ? CLIP_MARKER : "");
    }

    private static String clipScalar(String text) {
        if (text.length() <= MAX_SCAN_CHARS) {

            return text;
        }
        int keep = MAX_SCAN_CHARS;
        if (Character.isHighSurrogate(text.charAt(keep - 1)) && Character.isLowSurrogate(text.charAt(keep))) {
            keep--;
        }

        return text.substring(0, keep) + CLIP_MARKER;
    }

    /** Whether {@link #redact(String)} will clip this scalar before scanning it. */
    public boolean clips(String text) {

        return enabled && text != null && text.length() > MAX_SCAN_CHARS;
    }

    @Override
    public Object redactDeep(Object value) {
        if (!enabled || value == null) {

            return value;
        }
        if (value instanceof String text) {

            return redact(text);
        }
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> redacted = new LinkedHashMap<>();
            int keyCollision = 0;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                Object key = entry.getKey();
                boolean secretValue = false;
                if (key instanceof String name) {
                    secretValue = defaultRules && isSecretKey(name);
                    String safeName = redact(name);
                    if (!safeName.equals(name)) {
                        String baseName = safeName;
                        // Reserve original names, including ones not visited yet, so a
                        // scrubbed name never overwrites ordinary evidence or another key.
                        while (map.containsKey(safeName) || redacted.containsKey(safeName)) {
                            safeName = baseName + " (redacted key " + ++keyCollision + ")";
                        }
                    }
                    key = safeName;
                }
                redacted.put(key, secretValue ? REDACTED : redactDeep(entry.getValue()));
            }

            return redacted;
        }
        if (value instanceof List<?> list) {
            List<Object> redacted = new ArrayList<>(list.size());
            for (Object item : list) {
                redacted.add(redactDeep(item));
            }

            return redacted;
        }

        return value;
    }

    private static boolean isSecretKey(String key) {
        String normalized = key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");

        return SECRET_KEY_WORDS.stream().anyMatch(normalized::contains);
    }

    private static List<RedactionRule> builtInRules() {
        List<RedactionRule> result = new ArrayList<>(credentialRules());
        result.add(hexApiKeyRule());

        return result;
    }

    private static List<RedactionRule> credentialRules() {

        return List.of(
                literal(
                        "-----BEGIN [^-\\r\\n]*PRIVATE KEY-----.*?-----END [^-\\r\\n]*PRIVATE KEY-----",
                        Pattern.CASE_INSENSITIVE | Pattern.DOTALL),
                literal("\\b(?:AKIA|ASIA|A3T[A-Z0-9])[A-Z0-9]{16}\\b", 0),
                RedactionService::redactPropertyListValues,
                RedactionService::redactAssignments,
                new RegexRule(
                        Pattern.compile("(?i)bearer\\s+[A-Za-z0-9._~+/=-]{16,}"), matcher -> "Bearer " + REDACTED),
                literal("\\bgh[pousr]_[A-Za-z0-9]{36,}\\b", 0),
                literal("\\bsk-[A-Za-z0-9_-]{20,}\\b", 0),
                literal("\\bxox[baprs]-[A-Za-z0-9-]{10,}\\b", 0));
    }

    private static RedactionRule hexApiKeyRule() {

        return new RegexRule(
                HEX_API_KEY,
                matcher -> hexDigits(matcher.group()) >= MIN_API_KEY_HEX_DIGITS ? REDACTED : matcher.group());
    }

    private static int hexDigits(String apiKey) {
        int digits = 0;
        for (int index = "apikey_".length(); index < apiKey.length(); index++) {
            if (apiKey.charAt(index) != '_') {
                digits++;
            }
        }

        return digits;
    }

    /**
     * Replaces the {@code <string>} or {@code <data>} value of a property-list member whose key
     * name follows the structured secret-key policy, keeping both tags. An unclosed value
     * consumes the rest of the scanned scalar.
     */
    private static String redactPropertyListValues(String text) {
        Matcher matcher = PLIST_ENTRY.matcher(text);
        StringBuilder redacted = new StringBuilder();
        int copiedThrough = 0;
        int searchFrom = 0;
        while (searchFrom < text.length() && matcher.find(searchFrom)) {
            int valueStart = matcher.end();
            searchFrom = valueStart;
            if (!isSecretKey(matcher.group(1))) {
                continue;
            }
            int valueEnd = closingTag(text, valueStart, matcher.group(2));
            redacted.append(text, copiedThrough, valueStart).append(REDACTED);
            copiedThrough = valueEnd;
            searchFrom = valueEnd;
        }

        return redacted.append(text, copiedThrough, text.length()).toString();
    }

    // The next </tag> (or JSON-escaped <\/tag>), else the end of the text. Scanning stops at the
    // first closing tag, so successive values never rescan the same span.
    private static int closingTag(String text, int from, String tag) {
        String name = tag + ">";
        for (int open = text.indexOf('<', from); open >= 0; open = text.indexOf('<', open + 1)) {
            int slash = text.startsWith("\\/", open + 1) ? open + 2 : open + 1;
            if (text.startsWith("/", slash) && text.startsWith(name, slash + 1)) {

                return open;
            }
        }

        return text.length();
    }

    private static List<RedactionRule> customRules(List<String> patterns) {
        List<RedactionRule> customRules = new ArrayList<>(patterns.size());
        for (String pattern : patterns) {
            customRules.add(literal(pattern, 0));
        }

        return customRules;
    }

    private static RedactionRule literal(String pattern, int flags) {

        return new RegexRule(Pattern.compile(pattern, flags), matcher -> REDACTED);
    }

    private static String redactAssignments(String text) {
        Matcher matcher = ASSIGNMENT.matcher(text);
        StringBuilder redacted = new StringBuilder();
        int copiedThrough = 0;
        int searchFrom = 0;
        // Advance beyond each selected value before looking for another key. Quoted
        // values may contain spaces, escaped quotes or text that resembles another key.
        while (matcher.find(searchFrom)) {
            int valueStart = matcher.end();
            if (valueStart == text.length()) {
                break;
            }
            if (matcher.group(3).contains("=>") && !arrowPair(text, matcher, valueStart)) {
                // A lambda or arrow-function parameter such as token => token.trim().
                searchFrom = valueStart;
                continue;
            }
            int countEnd = tokenCountEnd(text, matcher, valueStart);
            if (countEnd > valueStart) {
                // Keep only the digits; whatever follows them is scanned as ordinary text.
                searchFrom = countEnd;
                continue;
            }
            AssignmentValue value = assignmentValue(text, valueStart);
            searchFrom = value.end();
            if (searchFrom == valueStart) {
                continue;
            }
            redacted.append(text, copiedThrough, valueStart).append(value.replacement());
            copiedThrough = searchFrom;
        }

        return redacted.append(text, copiedThrough, text.length()).toString();
    }

    private static AssignmentValue assignmentValue(String text, int start) {
        char quote = text.charAt(start);
        if (quote == '"' || quote == '\'') {
            int end = start + 1;
            boolean closed = false;
            while (end < text.length()) {
                char current = text.charAt(end++);
                if (current == '\\' && end < text.length()) {
                    end++;
                } else if (current == quote) {
                    closed = true;
                    break;
                }
            }

            // A missing closing quote consumes the remainder of the scanned scalar.
            return new AssignmentValue(
                    closed ? attachedSuffixEnd(text, end) : end,
                    quote + REDACTED + (closed ? Character.toString(quote) : ""));
        }
        int escapedQuote = escapedQuoteLength(text, start);
        if (escapedQuote > 0) {
            // A value quoted inside JSON-encoded text ends at the same escaped quote. Quotes
            // escaped one level deeper belong to the value; a missing close consumes the rest.
            String delimiter = text.substring(start, start + escapedQuote);
            int end = escapedQuoteEnd(text, start + escapedQuote, escapedQuote - 1);

            return end < 0
                    ? new AssignmentValue(text.length(), delimiter + REDACTED)
                    : new AssignmentValue(attachedSuffixEnd(text, end), delimiter + REDACTED + delimiter);
        }
        String prefix = "";
        if (text.regionMatches(true, start, "Bearer", 0, 6)
                && start + 6 < text.length()
                && Character.isWhitespace(text.charAt(start + 6))) {
            int tokenStart = start + 6;
            while (tokenStart < text.length() && Character.isWhitespace(text.charAt(tokenStart))) {
                tokenStart++;
            }
            if (tokenStart < text.length()) {
                prefix = "Bearer ";
                start = tokenStart;
            }
        }

        return new AssignmentValue(bareValueEnd(text, start), prefix + REDACTED);
    }

    private static int bareValueEnd(String text, int start) {
        // Treat every marker as an atomic span, including markers inside this value.
        // Its closing bracket must not expose any attached credential suffix.
        int end = start;
        while (end < text.length() && !Character.isWhitespace(text.charAt(end))) {
            if (text.startsWith(REDACTED, end)) {
                end += REDACTED.length();
            } else if (",}]".indexOf(text.charAt(end)) >= 0) {
                break;
            } else {
                end++;
            }
        }

        return end;
    }

    // Characters glued to a closing quote, as in the shell word "abc"def, continue the value;
    // a delimiter, quote or JSON escape after the quote ends it.
    private static int attachedSuffixEnd(String text, int closedAt) {
        if (closedAt == text.length()) {

            return closedAt;
        }
        char next = text.charAt(closedAt);
        boolean escape =
                next == '\\' && closedAt + 1 < text.length() && "nrt\"'".indexOf(text.charAt(closedAt + 1)) >= 0;
        boolean attached = !escape && (Character.isLetterOrDigit(next) || "_-+/=.~%@!#$*?^|\\[".indexOf(next) >= 0);

        return attached ? bareValueEnd(text, closedAt) : closedAt;
    }

    // The length of a JSON-escaped double quote (\" or \\\" ...) at start, else 0.
    private static int escapedQuoteLength(String text, int start) {
        int slashes = 0;
        while (slashes < 7 && start + slashes < text.length() && text.charAt(start + slashes) == '\\') {
            slashes++;
        }

        return slashes > 0 && start + slashes < text.length() && text.charAt(start + slashes) == '"' ? slashes + 1 : 0;
    }

    // The end of the first quote preceded by exactly this many backslashes, else -1.
    private static int escapedQuoteEnd(String text, int from, int slashes) {
        for (int quote = text.indexOf('"', from); quote >= 0; quote = text.indexOf('"', quote + 1)) {
            int run = 0;
            while (run <= slashes && quote - run - 1 >= from && text.charAt(quote - run - 1) == '\\') {
                run++;
            }
            if (run == slashes) {

                return quote + 1;
            }
        }

        return -1;
    }

    /**
     * Whether {@code key => value} is a key/value pair rather than a lambda parameter such as
     * {@code token => token.trim()}: a quoted key (plutil -p, PHP, Ruby), a quoted value (Perl,
     * Ruby), an environment-style name without lowercase letters (launchctl print), or a compact
     * {@code key=>value}, which stays conservative.
     */
    private static boolean arrowPair(String text, Matcher matcher, int valueStart) {
        char first = text.charAt(valueStart);
        String separator = matcher.group(3);
        if (!matcher.group(1).isEmpty()
                || first == '"'
                || first == '\''
                || escapedQuoteLength(text, valueStart) > 0
                || !Character.isWhitespace(separator.charAt(separator.length() - 1))) {

            return true;
        }

        return keyName(text, matcher).chars().noneMatch(Character::isLowerCase);
    }

    // A key directly after a JSON-escaped newline or tab starts after its escape letter.
    private static String keyName(String text, Matcher matcher) {
        String key = matcher.group(2);
        int start = matcher.start(2);

        return start > 0 && text.charAt(start - 1) == '\\' && "nrt".indexOf(key.charAt(0)) >= 0
                ? key.substring(1)
                : key;
    }

    /**
     * The end of a token count such as {@code max_tokens: 4096} or {@code promptTokenCount=87}, else
     * -1. The name must count tokens and carry no other secret word; the value must be plain digits
     * followed by a delimiter, never by more credential-like characters.
     */
    private static int tokenCountEnd(String text, Matcher matcher, int valueStart) {
        String name = matcher.group(2).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (!(name.contains("tokens") || name.contains("tokencount")) || isSecretKey(name.replace("token", ""))) {

            return -1;
        }
        int end = valueStart;
        while (end < text.length() && end - valueStart <= MAX_TOKEN_COUNT_DIGITS && isAsciiDigit(text.charAt(end))) {
            end++;
        }
        int digits = end - valueStart;
        if (digits == 0 || digits > MAX_TOKEN_COUNT_DIGITS) {

            return -1;
        }
        if (end == text.length()) {

            return end;
        }
        char next = text.charAt(end);
        if (Character.isWhitespace(next)
                || ",}]".indexOf(next) >= 0
                || next == '\\' && end + 1 < text.length() && "nrt".indexOf(text.charAt(end + 1)) >= 0) {

            return end;
        }
        // Closing punctuation (4096), 4096; 4096. or a closing quote) must not glue on more value.
        int after = next == '\\' && end + 1 < text.length() && text.charAt(end + 1) == '"' ? end + 2 : end + 1;
        boolean closing = after == end + 2 || "\"');.".indexOf(next) >= 0;
        boolean glued =
                after < text.length() && (Character.isLetterOrDigit(text.charAt(after)) || text.charAt(after) == '_');

        return closing && !glued ? end : -1;
    }

    private static boolean isAsciiDigit(char value) {

        return value >= '0' && value <= '9';
    }

    private record AssignmentValue(int end, String replacement) {}

    private interface RedactionRule {
        String redact(String value);
    }

    private record RegexRule(Pattern pattern, Replacement replacement) implements RedactionRule {

        @Override
        public String redact(String value) {
            Matcher matcher = pattern.matcher(value);
            StringBuilder redacted = new StringBuilder();
            while (matcher.find()) {
                matcher.appendReplacement(redacted, Matcher.quoteReplacement(replacement.replace(matcher)));
            }
            matcher.appendTail(redacted);

            return redacted.toString();
        }
    }

    private interface Replacement {
        String replace(Matcher matcher);
    }
}
