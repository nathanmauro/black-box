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
    // Words that make any name a credential name, in every form and letter case.
    private static final String STRONG_SECRET_WORDS =
            "api[_-]?key|secret|token|passwd|password|passphrase|authorization|private[_-]?key";
    // Weaker signals: names containing "credential", and environment-style names ending in _KEY
    // (STRIPE_KEY). Ordinary code uses both (credentials: 'include', ENTER_KEY = 13), so in text they
    // count only in configuration forms; see weakNameCounts.
    private static final Pattern STRONG_SECRET_NAME = Pattern.compile("(?i)" + STRONG_SECRET_WORDS);
    private static final Pattern ENV_KEY_NAME = Pattern.compile("(?:[A-Z0-9]++_)++KEY");
    // Anchored at token starts via lookbehind, with a bounded lazy prefix and a possessive
    // suffix: greedy runs flanking the keyword alternation backtrack quadratically on long
    // keyword-dense inputs (measured seconds of CPU per event), this shape stays linear. The
    // environment-style alternative is case-sensitive and possessive.
    private static final String SECRET_KEY = "(?<![A-Za-z0-9_-])(?:[A-Za-z0-9_-]{0,40}?(?:" + STRONG_SECRET_WORDS
            + "|credential)[A-Za-z0-9_-]*+|(?-i:(?:(?<=\\\\)[nrt])?(?:[A-Z0-9]++_)++KEY)(?![A-Za-z0-9_-]))";
    // The same shape with the strong words only, for export's whole-leaf fallback.
    private static final String STRONG_SECRET_KEY =
            "(?<![A-Za-z0-9_-])[A-Za-z0-9_-]{0,40}?(?:" + STRONG_SECRET_WORDS + ")[A-Za-z0-9_-]*+";
    // Clip oversized unredacted input before applying any rules. Anything past this
    // scan budget is dropped rather than stored unscanned; replacements may expand it.
    private static final int MAX_SCAN_CHARS = 50_000;
    private static final String CLIP_MARKER = " …[truncated]";
    // Backslashes before an escaped quote at the fourth JSON encoding level (\\\\\\\\\\\\\\\").
    private static final int MAX_ESCAPE_RUN = 15;
    // Bare, quoted, or JSON-escaped quoted keys (\" through four encoding levels). "=>" joins
    // plutil -p, launchctl print, Ruby and PHP pairs. Each run is bounded, so a near miss costs
    // constant work per start position.
    private static final Pattern ASSIGNMENT = Pattern.compile(
            "(?i)(\\\\{1," + MAX_ESCAPE_RUN + "}\"|[\"']|)(" + SECRET_KEY + ")\\1(\\s*(?:=>|[=:])\\s*)");
    // Text between a property-list key and its value: anything but markup, so raw or JSON-escaped
    // whitespace, JSON array separators, cat -n, grep and rg line-number or path gutters and diff
    // markers all fit, plus up to four XML comments. Bounded and possessive: a near miss scans at
    // most this far, and never past the next tag.
    private static final String PLIST_GAP = "[^<]{0,1024}+(?:<!--[^>]{0,256}-->[^<]{0,1024}+){0,4}+";
    private static final String PLIST_VALUE = "<(string|data|integer|real)>";
    // A property-list member: <key>NAME</key> (or <\/key>, slash-escaped), then its value element.
    private static final Pattern PLIST_ENTRY =
            Pattern.compile("<key>([^<]{1,128})<\\\\{0," + MAX_ESCAPE_RUN + "}/key>" + PLIST_GAP + PLIST_VALUE);
    // Another value element directly after a replaced one: the old and new lines of a diff.
    private static final Pattern PLIST_NEXT_VALUE = Pattern.compile(PLIST_GAP + PLIST_VALUE);
    // Text that ends with a key, or with a value this rule replaced, followed only by gap: its
    // value may open the next element of the same list (an Edit tool's structuredPatch lines).
    private static final Pattern PLIST_OPEN_TAIL = Pattern.compile("(?:<key>([^<]{1,128})<\\\\{0," + MAX_ESCAPE_RUN
            + "}/key>|<(?:string|data|integer|real)>\\[REDACTED]<\\\\{0," + MAX_ESCAPE_RUN
            + "}/(?:string|data|integer|real)>)" + PLIST_GAP + "\\z");
    // Provider keys shaped apikey_<hex>[_<hex>...], also directly after a JSON-escaped newline or
    // tab, a percent-encoded byte (%22, %3D) or a backslash-u escape (u000a). Possessive runs keep
    // this linear on long hexadecimal near misses.
    private static final Pattern HEX_API_KEY = Pattern.compile("(?:(?<![A-Za-z0-9_])|(?<=\\\\[nrt])"
            + "|(?<=%[0-9A-Fa-f]{2})|(?<=\\\\u[0-9A-Fa-f]{4}))apikey_[0-9a-fA-F]++(?:_[0-9a-fA-F]++)*+(?![A-Za-z0-9])");
    // Fewer hexadecimal digits than this is an identifier such as apikey_cafe, not a credential.
    private static final int MIN_API_KEY_HEX_DIGITS = 16;
    // A token count, never a credential, has at most this many digits.
    private static final int MAX_TOKEN_COUNT_DIGITS = 12;

    private final List<RedactionRule> exportRules = exportRules();

    private static List<RedactionRule> exportRules() {
        List<RedactionRule> result = new ArrayList<>(credentialRules());
        // JSON embedded in a text leaf may be escaped more than once. Conservatively
        // remove the rest of that leaf after a quoted credential key; do not guess
        // nested string boundaries and accidentally retain part of a secret. Weak names
        // ("credentials", STRIPE_KEY) are left to the exact rules: "WebAuthn.credentialAdded":
        // must not cost a model the rest of a document.
        result.add(literal(STRONG_SECRET_KEY + "\\\\*[\"']\\s*:\\s*.*", Pattern.CASE_INSENSITIVE | Pattern.DOTALL));
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

    // The structured, separator-insensitive secret-key policy for member names and property-list
    // keys: the durable hook's words plus passphrase, and environment-style _KEY names.
    private static final List<String> SECRET_KEY_WORDS = List.of(
            "apikey",
            "secret",
            "token",
            "passwd",
            "password",
            "passphrase",
            "authorization",
            "credential",
            "privatekey");

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
        String scanned = truncated ? clipped.substring(0, clipped.length() - CLIP_MARKER.length()) : clipped;
        String result = applyRules(scanned, rules);
        // HTML-safe JSON escapes for < and > (u003c and u003e after a backslash) and unicode-escaped
        // quotes can hide a whole credential pair. Scan a decoded copy as well; it replaces the
        // encoded result, decoded, only when it finds something the encoded text hid.
        String decoded = decodeEscapedMarkup(scanned);
        if (decoded != null) {
            String decodedResult = applyRules(decoded, rules);
            String decodedFirstPass = decodeEscapedMarkup(result);
            if (!decodedResult.equals(decodedFirstPass == null ? result : decodedFirstPass)) {
                result = decodedResult;
            }
        }

        return result + (truncated ? CLIP_MARKER : "");
    }

    private static String applyRules(String text, List<RedactionRule> rules) {
        String result = text;
        for (RedactionRule rule : rules) {
            result = rule.redact(result);
        }

        return result;
    }

    /**
     * The text with each escape of {@code <}, {@code >}, {@code "} or {@code '} as a backslash (one to
     * fifteen of them) and u003c, u003e, u0022 or u0027 (either hex case) replaced by that
     * character, or null when it has none.
     */
    private static String decodeEscapedMarkup(String text) {
        StringBuilder decoded = null;
        int copied = 0;
        for (int at = text.indexOf("u00"); at >= 0 && at + 5 <= text.length(); at = text.indexOf("u00", at + 1)) {
            char character = markupCharacter(text.charAt(at + 3), text.charAt(at + 4));
            int escapeStart = at;
            while (escapeStart > copied && at - escapeStart < MAX_ESCAPE_RUN && text.charAt(escapeStart - 1) == '\\') {
                escapeStart--;
            }
            if (character == 0 || escapeStart == at) {
                continue;
            }
            if (decoded == null) {
                decoded = new StringBuilder(text.length());
            }
            decoded.append(text, copied, escapeStart).append(character);
            copied = at + 5;
        }

        return decoded == null
                ? null
                : decoded.append(text, copied, text.length()).toString();
    }

    private static char markupCharacter(char high, char low) {
        char lower = Character.toLowerCase(low);
        if (high == '3' && lower == 'c') {

            return '<';
        }
        if (high == '3' && lower == 'e') {

            return '>';
        }
        if (high == '2' && low == '2') {

            return '"';
        }

        return high == '2' && low == '7' ? '\'' : 0;
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
            // A property-list key and its value can arrive as separate list elements, such as the
            // lines of an Edit tool's structuredPatch: the open key carries over to the next one.
            String openKey = null;
            for (Object item : list) {
                Object safe = redactDeep(item);
                if (defaultRules && safe instanceof String line) {
                    String leading = openKey == null ? line : redactLeadingPropertyListValue(line, openKey);
                    String tailKey = openSecretMember(leading);
                    openKey = tailKey != null ? tailKey : isGapOnly(leading) ? openKey : null;
                    safe = leading;
                } else {
                    openKey = null;
                }
                redacted.add(safe);
            }

            return redacted;
        }

        return value;
    }

    private static boolean isSecretKey(String key) {
        String normalized = key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");

        return SECRET_KEY_WORDS.stream().anyMatch(normalized::contains)
                || ENV_KEY_NAME.matcher(key.strip()).matches();
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
     * Replaces the {@code <string>}, {@code <data>}, {@code <integer>} or {@code <real>} value of a
     * property-list member whose key name follows the structured secret-key policy, keeping both
     * tags, and any value element that directly follows it (the old and new lines of a diff). An
     * unclosed value consumes the rest of the scanned scalar. A token count keeps its integer.
     */
    private static String redactPropertyListValues(String text) {
        Matcher matcher = PLIST_ENTRY.matcher(text);
        StringBuilder redacted = new StringBuilder();
        int copiedThrough = 0;
        int searchFrom = 0;
        while (searchFrom < text.length() && matcher.find(searchFrom)) {
            searchFrom = matcher.end();
            String name = matcher.group(1);
            if (!isSecretKey(name) || keepsTokenCount(text, matcher.end(), name, matcher.group(2))) {
                continue;
            }
            copiedThrough = redactValues(text, matcher.end(), matcher.group(2), redacted, copiedThrough);
            searchFrom = copiedThrough;
        }

        return redacted.append(text, copiedThrough, text.length()).toString();
    }

    /** Replaces a leading value element of a list item whose previous items left this key open. */
    private static String redactLeadingPropertyListValue(String text, String openKey) {
        Matcher value = PLIST_NEXT_VALUE.matcher(text);
        if (!value.lookingAt() || keepsTokenCount(text, value.end(), openKey, value.group(1))) {

            return text;
        }
        StringBuilder redacted = new StringBuilder();
        int copiedThrough = redactValues(text, value.end(), value.group(1), redacted, 0);

        return redacted.append(text, copiedThrough, text.length()).toString();
    }

    // Appends text up to each replaced value, starting with the one at valueStart, and returns
    // where copying resumes: after the last value replaced.
    private static int redactValues(
            String text, int valueStart, String tag, StringBuilder redacted, int copiedThrough) {
        int start = valueStart;
        String element = tag;
        while (true) {
            int valueEnd = closingTag(text, start, element);
            redacted.append(text, copiedThrough, start).append(REDACTED);
            copiedThrough = valueEnd;
            int afterTag = afterClosingTag(text, valueEnd, element);
            Matcher next = PLIST_NEXT_VALUE.matcher(text);
            if (afterTag < 0 || !next.region(afterTag, text.length()).lookingAt()) {

                return copiedThrough;
            }
            start = next.end();
            element = next.group(1);
        }
    }

    // The key, or "" after a replaced value, when text ends with an open secret member; else null.
    private static String openSecretMember(String text) {
        if (text.indexOf('<') < 0) {

            return null;
        }
        Matcher tail = PLIST_OPEN_TAIL.matcher(text);
        if (!tail.find()) {

            return null;
        }
        String key = tail.group(1);
        if (key == null) {

            return "";
        }

        return isSecretKey(key) ? key : null;
    }

    private static boolean isGapOnly(String text) {

        return text.length() <= 1024 && text.indexOf('<') < 0;
    }

    // A token count such as <key>tokenCount</key><integer>42</integer> is not a credential.
    private static boolean keepsTokenCount(String text, int valueStart, String key, String tag) {
        if (!"integer".equals(tag) || !countsTokens(key)) {

            return false;
        }
        int end = valueStart;
        while (end < text.length() && end - valueStart <= MAX_TOKEN_COUNT_DIGITS && isAsciiDigit(text.charAt(end))) {
            end++;
        }

        return end > valueStart && end - valueStart <= MAX_TOKEN_COUNT_DIGITS && afterClosingTag(text, end, tag) >= 0;
    }

    // A name that counts tokens (max_tokens, tokenCount) and carries no other secret word.
    private static boolean countsTokens(String name) {
        String normalized = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");

        return (normalized.contains("tokens") || normalized.contains("tokencount"))
                && !isSecretKey(normalized.replace("token", ""));
    }

    // The next </tag> (or slash-escaped <\/tag>), else the end of the text. Scanning stops at the
    // first closing tag, so successive values never rescan the same span.
    private static int closingTag(String text, int from, String tag) {
        for (int open = text.indexOf('<', from); open >= 0; open = text.indexOf('<', open + 1)) {
            if (afterClosingTag(text, open, tag) >= 0) {

                return open;
            }
        }

        return text.length();
    }

    // The index after the closing tag that starts at open, else -1.
    private static int afterClosingTag(String text, int open, String tag) {
        if (open >= text.length() || text.charAt(open) != '<') {

            return -1;
        }
        int slash = open + 1 + Math.min(backslashRun(text, open + 1), MAX_ESCAPE_RUN);
        int end = slash + 1 + tag.length();
        boolean closes = text.startsWith("/", slash) && text.startsWith(tag, slash + 1) && text.startsWith(">", end);

        return closes ? end + 1 : -1;
    }

    private static int backslashRun(String text, int from) {
        int end = from;
        while (end < text.length() && text.charAt(end) == '\\') {
            end++;
        }

        return end - from;
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
            String name = keyName(text, matcher);
            boolean arrow = matcher.group(3).contains("=>");
            if (!STRONG_SECRET_NAME.matcher(name).find() && !weakNameCounts(text, name, matcher, arrow)) {
                // Ordinary code, such as credentials: 'include' or ENTER_KEY = 13.
                searchFrom = valueStart;
                continue;
            }
            if (arrow && !arrowPair(text, matcher, valueStart)) {
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
            // launchctl print shows an environment value unquoted, through the end of its line.
            boolean wholeLine = arrow && isEnvironmentName(name) && startsLine(text, keyStart(text, matcher));
            AssignmentValue value = assignmentValue(text, valueStart, wholeLine, lineBreakRun(text, matcher));
            searchFrom = value.end();
            if (searchFrom == valueStart) {
                continue;
            }
            redacted.append(text, copiedThrough, valueStart).append(value.replacement());
            copiedThrough = searchFrom;
        }

        return redacted.append(text, copiedThrough, text.length()).toString();
    }

    /**
     * Whether a name that is a credential name only through "credential" or an environment-style
     * {@code _KEY} suffix counts in this text form, with its value on the same line. A {@code =>}
     * pair (plutil -p, launchctl print) always counts. An environment-style name such as
     * {@code SBA_DB_CREDENTIAL} or {@code STRIPE_KEY} also counts quoted (JSON) or in
     * {@code NAME=value} and {@code NAME: value}, but not in a spaced code assignment
     * ({@code ENTER_KEY = 13}). Ordinary code and data stay: a name with lowercase letters
     * ({@code credentials: 'include'}, {@code "credential-management":}), a block colon
     * ({@code != FAKE_KEY:}) and a shell expansion ({@code EVENT_KEY="$(...)"}, a reference).
     */
    private static boolean weakNameCounts(String text, String name, Matcher matcher, boolean arrow) {
        String separator = matcher.group(3);
        if (separator.indexOf('\n') >= 0 || separator.indexOf('\r') >= 0 || shellExpansion(text, matcher.end())) {

            return false;
        }
        if (arrow) {

            return true;
        }
        if (!isEnvironmentName(name)) {

            return false;
        }

        return !matcher.group(1).isEmpty()
                || separator.indexOf('=') < 0
                || !Character.isWhitespace(separator.charAt(0));
    }

    // A value that starts with $ (after an optional plain or escaped quote): $VAR, ${VAR} or $(command).
    private static boolean shellExpansion(String text, int valueStart) {
        int at = valueStart + escapedQuoteLength(text, valueStart);
        if (at == valueStart && at < text.length() && (text.charAt(at) == '"' || text.charAt(at) == '\'')) {
            at++;
        }

        return at < text.length() && text.charAt(at) == '$';
    }

    private static boolean isEnvironmentName(String name) {

        return name.chars().noneMatch(Character::isLowerCase);
    }

    // Where a key begins: its quote, or the character after a JSON escape letter glued to it.
    private static int keyStart(String text, Matcher matcher) {

        return keyName(text, matcher).length() == matcher.group(2).length() ? matcher.start() : matcher.start(2) + 1;
    }

    /**
     * Whether only spaces, tabs and JSON-escaped tabs stand between a key and the start of its
     * line: a raw or JSON-escaped line break, the start of the text, or the quote that opens a
     * JSON string. A key named inline in prose ({@code see `SBA_X_TOKEN => …`}) does not start one.
     */
    private static boolean startsLine(String text, int keyStart) {
        int at = keyStart;
        for (int steps = 0; steps < 256 && at > 0; steps++) {
            char previous = text.charAt(at - 1);
            if (previous == '\n' || previous == '\r' || previous == '"') {

                return true;
            }
            int run = at >= 2 && "nrt".indexOf(previous) >= 0 ? backslashRunBefore(text, at - 1) : 0;
            if (run > 0) {
                if (previous != 't') {

                    return true;
                }
                at -= run + 1;
            } else if (previous == ' ' || previous == '\t') {
                at--;
            } else {

                return false;
            }
        }

        return at == 0;
    }

    private static int backslashRunBefore(String text, int end) {
        int run = 0;
        while (end - 1 - run >= 0 && text.charAt(end - 1 - run) == '\\') {
            run++;
        }

        return run;
    }

    /**
     * The backslashes in a JSON-escaped line break at the key's encoding level ({@code \n} is 1,
     * {@code \\n} is 2), read from the escape before the key or from its escaped quote; 0 in raw text.
     */
    private static int lineBreakRun(String text, Matcher matcher) {
        int quote = matcher.group(1).length();
        if (quote > 1) {

            return quote / 2;
        }
        if (keyName(text, matcher).length() == matcher.group(2).length()) {

            return 0;
        }

        return backslashRunBefore(text, matcher.start(2));
    }

    private static AssignmentValue assignmentValue(String text, int start, boolean wholeLine, int lineBreakRun) {
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
        if (text.startsWith("{length = ", start)) {

            // A plutil -p data value: {length = 16, bytes = 0x0123...}.
            return new AssignmentValue(lineEnd(text, start, lineBreakRun, true), REDACTED);
        }
        if (wholeLine) {

            return new AssignmentValue(lineEnd(text, start, lineBreakRun, false), REDACTED);
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
            char current = text.charAt(end);
            if (text.startsWith(REDACTED, end)) {
                end += REDACTED.length();
            } else if (",}]".indexOf(current) >= 0) {
                break;
            } else if (current == '\\') {
                // A JSON-escaped line break or tab, at any encoding level, ends the value as raw
                // whitespace does; the next entry of an escaped env or launchctl listing follows it.
                int run = backslashRun(text, end);
                if (end + run < text.length() && "nrt".indexOf(text.charAt(end + run)) >= 0) {
                    break;
                }
                end += run;
            } else {
                end++;
            }
        }

        return end;
    }

    /**
     * The end of a displayed line from start: a raw line break, a JSON-escaped one at any encoding
     * level or, in JSON-escaped text ({@code lineBreakRun > 0}), the closing quote of the string
     * that holds the display. With {@code throughBrace}, the first {@code }} on the line ends it too,
     * included.
     */
    private static int lineEnd(String text, int start, int lineBreakRun, boolean throughBrace) {
        int end = start;
        while (end < text.length()) {
            char current = text.charAt(end);
            if (current == '\n' || current == '\r' || current == '"' && lineBreakRun == 1) {

                return end;
            }
            if (current == '}' && throughBrace) {

                return end + 1;
            }
            if (current != '\\') {
                end++;
                continue;
            }
            int run = backslashRun(text, end);
            char next = end + run < text.length() ? text.charAt(end + run) : 0;
            if (next == 'n' || next == 'r' || next == '"' && run == lineBreakRun - 1) {

                return end;
            }
            end += run;
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
        while (slashes < MAX_ESCAPE_RUN && start + slashes < text.length() && text.charAt(start + slashes) == '\\') {
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
        if (!countsTokens(matcher.group(2))) {

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
