package dev.nathan.sbaagentic.recording.internal.application;

import dev.nathan.sbaagentic.recording.ExportRedactor;
import dev.nathan.sbaagentic.recording.IngestionProperties;
import dev.nathan.sbaagentic.recording.IngestionRedactor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
    private static final Pattern CREDENTIAL_WORD = Pattern.compile("(?i)credential");
    private static final Pattern ENV_KEY_NAME = Pattern.compile("(?:[A-Z0-9]++_)++KEY");
    // The longest name the text rules read: a longer run of name characters is not a name.
    private static final int MAX_NAME_CHARS = 256;
    // A name in text: the whole run of name characters that starts a token, after a non-name character
    // or a unicode-escaped quote (\\u0022, as System.Text.Json writes one), with the letter of a JSON
    // escape glued before it (the t of \tSBA_DB_PASSWORD) counted as one more character. The run is
    // possessive and capped, so a near miss costs one pass over it; which names are credential names is
    // decided outside the pattern (see redactAssignments), wherever the credential word stands in the
    // name.
    private static final String NAME = "(?:(?<![A-Za-z0-9_-])|(?<=\\\\u00(?:22|27)))[A-Za-z0-9_-]{1,"
            + (MAX_NAME_CHARS + 1) + "}+(?![A-Za-z0-9_-])";
    // Clip oversized unredacted input before applying any rules. Anything past this
    // scan budget is dropped rather than stored unscanned; replacements may expand it.
    private static final int MAX_SCAN_CHARS = 50_000;
    private static final String CLIP_MARKER = " …[truncated]";
    // Backslashes before an escaped quote at the fourth JSON encoding level (\\\\\\\\\\\\\\\").
    private static final int MAX_ESCAPE_RUN = 15;
    // Bare, quoted, JSON-escaped quoted (\" through four encoding levels) or unicode-escaped quoted
    // names followed by a separator. "=>" joins plutil -p, launchctl print, Ruby and PHP pairs, also
    // with its > written as an HTML-safe escape (=\\u003e). Each run is bounded or possessive, so a near
    // miss costs constant work per start, and only a credential name's pair is redacted.
    private static final Pattern ASSIGNMENT = Pattern.compile("(?i)(\\\\{1," + MAX_ESCAPE_RUN + "}\"|\\\\{1,"
            + MAX_ESCAPE_RUN + "}u00(?:22|27)|[\"']|)(" + NAME + ")\\1(\\s*+(?:=>|=\\\\{1," + MAX_ESCAPE_RUN
            + "}u003e|[=:])\\s*+)");
    // Export's whole-leaf fallback starts at a quoted name, raw or JSON-escaped, and its colon.
    private static final Pattern QUOTED_NAME_COLON =
            Pattern.compile("(?<![A-Za-z0-9_-])([A-Za-z0-9_-]{1," + (MAX_NAME_CHARS + 1) + "}+)\\\\*+[\"']\\s*+:");
    // NAME = value with a space on each side, as PlistBuddy Print shows an environment entry.
    private static final Pattern SPACED_EQUALS = Pattern.compile("[ \\t]++=[ \\t]++");
    // Text between a property-list key and its value: anything but markup, so raw or JSON-escaped
    // whitespace, JSON array separators, cat -n, grep and rg line-number or path gutters and diff
    // markers all fit, plus up to four XML comments. Bounded and possessive: a near miss scans at
    // most this far, and never past the next tag.
    private static final String PLIST_GAP = "[^<]{0,1024}+(?:<!--[^>]{0,256}-->[^<]{0,1024}+){0,4}+";
    private static final String PLIST_VALUE = "<(string|data|integer|real)>";
    // What names a property-list value: <key>NAME</key> (or <\/key>, slash-escaped), or a flag that is
    // one string of an argument array (<string>--api-key</string>), whose value is the next string.
    private static final String PLIST_NAME = "(?:<key>([^<]{1,128})<\\\\{0," + MAX_ESCAPE_RUN
            + "}/key>|<string>(--[A-Za-z0-9][A-Za-z0-9._-]{0,127})<\\\\{0," + MAX_ESCAPE_RUN + "}/string>)";
    private static final Pattern PLIST_ENTRY = Pattern.compile(PLIST_NAME + PLIST_GAP + PLIST_VALUE);
    // Another value element directly after a replaced one: the old and new lines of a diff.
    private static final Pattern PLIST_NEXT_VALUE = Pattern.compile(PLIST_GAP + PLIST_VALUE);
    // Text that ends with a name, or with a value this rule replaced, followed only by gap: its
    // value may open the next element of the same list (an Edit tool's structuredPatch lines).
    private static final Pattern PLIST_OPEN_TAIL = Pattern.compile("(?:" + PLIST_NAME
            + "|<(?:string|data|integer|real)>\\[REDACTED]<\\\\{0," + MAX_ESCAPE_RUN
            + "}/(?:string|data|integer|real)>)" + PLIST_GAP + "\\z");
    // Longer text that starts with -- is not a flag.
    private static final int MAX_FLAG_CHARS = 130;
    // A command-line flag of at most MAX_FLAG_CHARS characters, read as one possessive run, so a near
    // miss stays linear; isSecretFlag decides whether its argument is a credential.
    private static final Pattern FLAG = Pattern.compile(
            "(?:(?<![A-Za-z0-9_-])|(?<=\\\\[nrt]))--[A-Za-z0-9._-]{1," + (MAX_FLAG_CHARS - 2) + "}+(?![A-Za-z0-9._-])");
    // English function words that prose puts after a flag it names ("--api-key or --token").
    private static final Set<String> PROSE_WORDS = Set.of(
            "a", "an", "and", "as", "at", "by", "for", "from", "if", "in", "into", "is", "it", "of", "on", "or", "than",
            "that", "the", "then", "this", "to", "when", "with");
    // Matched only against a flag of at most MAX_FLAG_CHARS, so its segment loop may backtrack.
    private static final Pattern SECRET_FLAG_NAME = Pattern.compile("(?i)--(?:[a-z0-9]+[._-])*"
            + "(?<!page[._-]|next[._-]|continuation[._-]|starting[._-]|sync[._-])"
            + "(?:api[_-]?key|private[_-]?key|token|secret|passwd|password|passphrase|authorization)");
    // Commands that write a credential into launchd's environment or a property list, followed by the
    // whitespace before the value: launchctl setenv NAME VALUE, PlistBuddy Set or Add :path:NAME
    // [type] VALUE, plutil -replace or -insert keypath -type VALUE, defaults write domain NAME [-type]
    // VALUE, and security add-generic-password ... -w VALUE.
    private static final Pattern WRITE_COMMAND = Pattern.compile("(?:(?<![A-Za-z0-9_-])|(?<=\\\\[nrt]))(?:"
            + "setenv[ \\t]++(?<setenv>[A-Za-z0-9_.-]{1,128}+)"
            + "|(?i:set|add)[ \\t]++:(?<buddy>(?:[^:\\s\"'\\\\]{1,128}+:){0,16}+[^:\\s\"'\\\\]{1,128}+)"
            + "(?:[ \\t]++(?:string|data|integer|real|bool|date)(?![A-Za-z0-9_-]))?+"
            + "|-(?:replace|insert)[ \\t]++(?<plutil>[^\\s\"'\\\\]{1,512}+)"
            + "[ \\t]++-(?:string|data|integer|float|bool|date|json|xml)(?![A-Za-z0-9_-])"
            + "|defaults[ \\t]++write[ \\t]++[^\\s\"'\\\\]{1,256}+[ \\t]++(?<defaults>[^\\s\"'\\\\-][^\\s\"'\\\\]{0,127}+)"
            + "(?:[ \\t]++-(?:string|data|int|integer|float|bool|boolean|date)(?![A-Za-z0-9_-]))?+"
            + "|add-(?:generic|internet)-password(?<security>)[^\\r\\n]{0,512}?[ \\t]-w"
            + ")[ \\t]++");
    // The lines that open and close a private key block, in any letter case.
    private static final Pattern PRIVATE_KEY_BEGIN =
            Pattern.compile("-----BEGIN [^-\\r\\n]*PRIVATE KEY-----", Pattern.CASE_INSENSITIVE);
    private static final Pattern PRIVATE_KEY_END =
            Pattern.compile("-----END [^-\\r\\n]*PRIVATE KEY-----", Pattern.CASE_INSENSITIVE);
    // Provider keys shaped apikey_<hex>[_<hex>...]. Possessive runs keep this linear on long
    // hexadecimal near misses; where a key may start is decided by keyBoundary.
    private static final Pattern HEX_API_KEY = Pattern.compile("apikey_[0-9a-fA-F]++(?:_[0-9a-fA-F]++)*+");
    // Fewer hexadecimal digits than this is an identifier such as apikey_cafe, not a credential.
    private static final int MIN_API_KEY_HEX_DIGITS = 16;
    // With this many, the shape is a credential whatever text it is glued to, such as the length byte
    // that a binary property list stores before a string (Hapikey_...).
    private static final int GLUED_API_KEY_HEX_DIGITS = 32;
    // A token count, never a credential, has at most this many digits.
    private static final int MAX_TOKEN_COUNT_DIGITS = 12;
    // How far back a key may start its displayed line: indentation plus a line-number or path gutter.
    private static final int MAX_LINE_PREFIX = 256;
    // What may stand between the start of a displayed line and a key that starts it, once escaped tabs
    // read as tabs: indentation after cat -n or file-view line numbers or grep and rg gutters with or
    // without a path (path:12:, path-13-, 12:), then quote marks, Markdown headings, list or diff
    // markers, and an opening code span or bold marker, whose closer then ends the value (group 1).
    // Every blank run is possessive: no part of the pattern after one can start with a blank, and
    // nested greedy blank runs backtracked quadratically on a long run of blanks before other text.
    private static final Pattern LINE_PREFIX = Pattern.compile("[ \\t]*+(?:\\d{1,9}(?:\\t|→)"
            + "|(?:[^\\s:]{1,200}?[:-])?(?:\\d{1,9}[:-])?)"
            + "(?:[ \\t]*+(?:>|(?:#{1,6}|\\d{1,3}[.)]|[-+*])(?=[ \\t])))*+[ \\t]*+(`|\\*\\*|__)?");
    // How far a launchctl print value may continue on following lines before a tab-indented entry.
    private static final int MAX_CONTINUATION_CHARS = 4096;
    // Text after an arrow that is searched for a lambda's own parameter name.
    private static final int MAX_ARROW_BODY_CHARS = 256;

    private static final List<RedactionRule> BUILT_IN_RULES = builtInRules();
    private final List<RedactionRule> exportRules = exportRules();

    private static List<RedactionRule> exportRules() {
        List<RedactionRule> result = new ArrayList<>(credentialRules());
        // JSON embedded in a text leaf may be escaped more than once. Conservatively
        // remove the rest of that leaf after a quoted credential key; do not guess
        // nested string boundaries and accidentally retain part of a secret. Weak names
        // ("credentials", STRIPE_KEY) are left to the exact rules: "WebAuthn.credentialAdded":
        // must not cost a model the rest of a document.
        result.add(RedactionService::redactAfterQuotedCredentialName);
        // Last, so a quoted apikey_<hex> member name still reaches the rule above.
        result.add(RedactionService::redactHexApiKeys);

        return result;
    }

    // Replaces the rest of the text from the first quoted name that holds a strong credential word and
    // is followed by a colon, the name included.
    private static String redactAfterQuotedCredentialName(String text) {
        Matcher matcher = QUOTED_NAME_COLON.matcher(text);
        while (matcher.find()) {
            if (STRONG_SECRET_NAME.matcher(matcher.group(1)).find()) {

                return text.substring(0, matcher.start()) + REDACTED;
            }
        }

        return text;
    }

    @Override
    public String redactForExport(String text) {
        if (text == null)

            return null;

        return redactDefaults(text, exportRules);
    }

    @Override
    public String redactForSummary(String text) {
        if (text == null)

            return null;

        return redactDefaults(text, BUILT_IN_RULES);
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
        this.rules = defaultRules ? BUILT_IN_RULES : customRules(customPatterns);
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
        // quotes can hide a whole credential pair. Scan the first result again with those escapes
        // decoded: spans it replaced stay replaced, so this scan can only add replacements, and its
        // result is kept, decoded, only when it does.
        String decoded = decodeEscapedMarkup(result);
        if (decoded != null) {
            String decodedResult = applyRules(decoded, rules);
            if (!decodedResult.equals(decoded)) {
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
            // A property-list name and its value can arrive as separate list elements, such as the
            // lines of an Edit tool's structuredPatch: the open name carries over to the next one.
            String openName = null;
            for (Object item : list) {
                Object safe = redactDeep(item);
                if (defaultRules && safe instanceof String line) {
                    String leading = openName == null ? line : redactLeadingPropertyListValue(line, openName);
                    String tailName = openSecretMember(leading);
                    if ("".equals(tailName) && openName != null && isFlag(openName)) {
                        // A flag's replaced value: the chain still stops at the next flag.
                        tailName = openName;
                    }
                    openName = tailName != null ? tailName : isGapOnly(leading) ? openName : null;
                    safe = leading;
                } else {
                    openName = null;
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

    // A command-line flag that takes a credential: its name ends with a credential word, as in
    // --api-key, --db-password, --client-secret and --auth-token. A flag that names where a credential
    // is (--secret-id, --token-file, --api-key-env), reads one (--password-stdin) or pages results
    // (--next-token) does not, and neither does a token limit (--max-tokens).
    private static boolean isSecretFlag(String flag) {

        return flag.length() <= MAX_FLAG_CHARS && SECRET_FLAG_NAME.matcher(flag).matches();
    }

    private static boolean isFlag(String name) {

        return name.startsWith("-");
    }

    private static List<RedactionRule> builtInRules() {
        List<RedactionRule> result = new ArrayList<>(credentialRules());
        result.add(RedactionService::redactHexApiKeys);

        return List.copyOf(result);
    }

    private static List<RedactionRule> credentialRules() {

        return List.of(
                RedactionService::redactPrivateKeyBlocks,
                literal("\\b(?:AKIA|ASIA|A3T[A-Z0-9])[A-Z0-9]{16}\\b", 0),
                RedactionService::redactPropertyListValues,
                RedactionService::redactAssignments,
                RedactionService::redactSecretFlags,
                RedactionService::redactWriteCommands,
                new RegexRule(
                        Pattern.compile("(?i)bearer\\s+[A-Za-z0-9._~+/=-]{16,}"), matcher -> "Bearer " + REDACTED),
                literal("\\bgh[pousr]_[A-Za-z0-9]{36,}\\b", 0),
                literal("\\bsk-[A-Za-z0-9_-]{20,}\\b", 0),
                literal("\\bxox[baprs]-[A-Za-z0-9-]{10,}\\b", 0));
    }

    /**
     * Replaces each complete private key block, from a BEGIN line through the first END line after it.
     * When no END line follows a BEGIN line, none follows a later one either, so the scan stops there
     * rather than searching the rest of the text again for every later BEGIN line.
     */
    private static String redactPrivateKeyBlocks(String text) {
        Matcher begin = PRIVATE_KEY_BEGIN.matcher(text);
        Matcher end = PRIVATE_KEY_END.matcher(text);
        StringBuilder redacted = null;
        int copiedThrough = 0;
        int searchFrom = 0;
        while (searchFrom < text.length() && begin.find(searchFrom) && end.find(begin.end())) {
            if (redacted == null) {
                redacted = new StringBuilder(text.length());
            }
            redacted.append(text, copiedThrough, begin.start()).append(REDACTED);
            copiedThrough = end.end();
            searchFrom = end.end();
        }

        return redacted == null
                ? text
                : redacted.append(text, copiedThrough, text.length()).toString();
    }

    /**
     * Replaces each provider key shaped apikey_<hex>[_<hex>...] that has at least 16 hexadecimal
     * digits and starts a token (see {@link #keyBoundary}), or at least 32 wherever it starts.
     * Characters glued after the hexadecimal run stay, as in apikey_...PID or apikey_...ready.
     */
    private static String redactHexApiKeys(String text) {
        Matcher matcher = HEX_API_KEY.matcher(text);
        StringBuilder redacted = null;
        int copiedThrough = 0;
        int searchFrom = 0;
        while (searchFrom < text.length() && matcher.find(searchFrom)) {
            int digits = hexDigits(matcher.group());
            if (digits < GLUED_API_KEY_HEX_DIGITS
                    && (digits < MIN_API_KEY_HEX_DIGITS || !keyBoundary(text, matcher.start()))) {
                // Another key may start inside this near miss (apikey_apikey_<hex>).
                searchFrom = matcher.start() + 1;
                continue;
            }
            if (redacted == null) {
                redacted = new StringBuilder(text.length());
            }
            redacted.append(text, copiedThrough, matcher.start()).append(REDACTED);
            copiedThrough = matcher.end();
            searchFrom = matcher.end();
        }

        return redacted == null
                ? text
                : redacted.append(text, copiedThrough, text.length()).toString();
    }

    // Whether a key starting here starts a token: after a non-word character, a JSON-escaped line break
    // or tab (\n), a percent-encoded byte (%22, %3D) or a backslash-u escape (u000a after a backslash).
    private static boolean keyBoundary(String text, int start) {
        if (start == 0 || !isWordChar(text.charAt(start - 1))) {

            return true;
        }
        char previous = text.charAt(start - 1);
        if (start >= 2 && text.charAt(start - 2) == '\\' && "nrt".indexOf(previous) >= 0) {

            return true;
        }
        if (start >= 3 && text.charAt(start - 3) == '%' && isHexDigit(text.charAt(start - 2)) && isHexDigit(previous)) {

            return true;
        }

        return start >= 6
                && text.charAt(start - 6) == '\\'
                && text.charAt(start - 5) == 'u'
                && isHexDigit(text.charAt(start - 4))
                && isHexDigit(text.charAt(start - 3))
                && isHexDigit(text.charAt(start - 2))
                && isHexDigit(previous);
    }

    private static boolean isWordChar(char value) {

        return value == '_' || value < 128 && Character.isLetterOrDigit(value);
    }

    private static boolean isHexDigit(char value) {

        return Character.digit(value, 16) >= 0 && value < 128;
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
     * property-list member whose key name follows the structured secret-key policy, or of a
     * credential flag's argument, keeping both tags, and any value element that directly follows it
     * (the old and new lines of a diff; for a flag, up to the next flag). An unclosed value consumes
     * the rest of the scanned scalar. A token count keeps its integer.
     */
    private static String redactPropertyListValues(String text) {
        Matcher matcher = PLIST_ENTRY.matcher(text);
        StringBuilder redacted = new StringBuilder();
        int copiedThrough = 0;
        int searchFrom = 0;
        while (searchFrom < text.length() && matcher.find(searchFrom)) {
            String key = matcher.group(1);
            String name = key != null ? key : matcher.group(2);
            String tag = matcher.group(3);
            // The value element may itself name another member, such as the next flag of an array.
            searchFrom = matcher.start(3) - 1;
            boolean secret = key != null ? isSecretKey(name) : isSecretFlag(name);
            if (!secret || keepsTokenCount(text, matcher.end(), name, tag)) {
                continue;
            }
            if (isFlag(name) && startsFlag(text, matcher.end())) {
                // A flag without an argument, directly followed by the next flag.
                continue;
            }
            copiedThrough = redactValues(text, matcher.end(), tag, isFlag(name), redacted, copiedThrough);
            searchFrom = copiedThrough;
        }

        return redacted.append(text, copiedThrough, text.length()).toString();
    }

    /** Replaces a leading value element of a list item whose previous items left this name open. */
    private static String redactLeadingPropertyListValue(String text, String openName) {
        Matcher value = PLIST_NEXT_VALUE.matcher(text);
        if (!value.lookingAt()
                || keepsTokenCount(text, value.end(), openName, value.group(1))
                || isFlag(openName) && startsFlag(text, value.end())) {

            return text;
        }
        StringBuilder redacted = new StringBuilder();
        int copiedThrough = redactValues(text, value.end(), value.group(1), isFlag(openName), redacted, 0);

        return redacted.append(text, copiedThrough, text.length()).toString();
    }

    // Appends text up to each replaced value, starting with the one at valueStart, and returns
    // where copying resumes: after the last value replaced. After a flag, a value that is itself a
    // flag ends the chain.
    private static int redactValues(
            String text, int valueStart, String tag, boolean flag, StringBuilder redacted, int copiedThrough) {
        int start = valueStart;
        String element = tag;
        while (true) {
            int valueEnd = closingTag(text, start, element);
            redacted.append(text, copiedThrough, start).append(REDACTED);
            copiedThrough = valueEnd;
            int afterTag = afterClosingTag(text, valueEnd, element);
            Matcher next = PLIST_NEXT_VALUE.matcher(text);
            if (afterTag < 0
                    || !next.region(afterTag, text.length()).lookingAt()
                    || flag && startsFlag(text, next.end())) {

                return copiedThrough;
            }
            start = next.end();
            element = next.group(1);
        }
    }

    private static boolean startsFlag(String text, int at) {

        return at < text.length() && text.charAt(at) == '-';
    }

    // The name, or "" after a replaced value, when text ends with an open secret member; else null.
    private static String openSecretMember(String text) {
        if (text.indexOf('<') < 0) {

            return null;
        }
        Matcher tail = PLIST_OPEN_TAIL.matcher(text);
        if (!tail.find()) {

            return null;
        }
        String key = tail.group(1);
        String flag = tail.group(2);
        if (key == null && flag == null) {

            return "";
        }

        return key != null ? isSecretKey(key) ? key : null : isSecretFlag(flag) ? flag : null;
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
            String separator = matcher.group(3);
            boolean arrow = separator.contains("=>")
                    || separator.toLowerCase(Locale.ROOT).contains("u003e");
            boolean strong = name.length() <= MAX_NAME_CHARS
                    && STRONG_SECRET_NAME.matcher(name).find();
            boolean weak = !strong && name.length() <= MAX_NAME_CHARS && weakSecretName(name);
            if (!strong && !(weak && weakNameCounts(text, name, matcher, arrow))) {
                // Ordinary code, such as credentials: 'include' or ENTER_KEY = 13.
                searchFrom = valueStart;
                continue;
            }
            boolean bareKey = matcher.group(1).isEmpty();
            // PlistBuddy Print shows an environment entry as NAME = value through the end of its line.
            // Code assigns lowercase names the same way (token = { value: "" }), so those keep the
            // bare value.
            boolean spaced = strong
                    && bareKey
                    && isEnvironmentName(name)
                    && SPACED_EQUALS.matcher(separator).matches();
            LineStart line = arrow || spaced ? lineStart(text, keyStart(text, matcher)) : LineStart.NONE;
            if (arrow && !arrowPair(text, matcher, valueStart, name, line)) {
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
            // launchctl print shows an environment value unquoted, through the end of its line, and
            // plutil -p does not escape a quote inside a quoted value.
            boolean wholeLine = line.starts() && (spaced || bareKey || isEnvironmentName(name));
            boolean lastQuote = arrow && line.starts() && !bareKey;
            boolean continues = arrow && wholeLine && bareKey && line.tabsOnly();
            int level = line.starts() ? line.level() : lineBreakRun(text, matcher);
            AssignmentValue value =
                    assignmentValue(text, valueStart, level, wholeLine, lastQuote, continues, line.closer());
            searchFrom = value.end();
            if (searchFrom == valueStart) {
                continue;
            }
            redacted.append(text, copiedThrough, valueStart).append(value.replacement());
            copiedThrough = searchFrom;
        }

        return redacted.append(text, copiedThrough, text.length()).toString();
    }

    // A name that only the weaker signals make a credential name: "credential" in any letter case, or
    // an environment-style name ending in _KEY.
    private static boolean weakSecretName(String name) {

        return CREDENTIAL_WORD.matcher(name).find()
                || ENV_KEY_NAME.matcher(name).matches();
    }

    /**
     * Whether a name that is a credential name only through "credential" or an environment-style
     * {@code _KEY} suffix counts in this text form, with its value on the same line. A {@code =>}
     * pair (plutil -p, launchctl print) always counts, since those displays show values, never shell
     * references. An environment-style name such as {@code SBA_DB_CREDENTIAL} or {@code STRIPE_KEY}
     * also counts quoted (JSON) or in {@code NAME=value} and {@code NAME: value}, but not in a spaced
     * code assignment ({@code ENTER_KEY = 13}). Ordinary code and data stay: a name with lowercase
     * letters ({@code credentials: 'include'}, {@code "credential-management":}), a block colon
     * ({@code != FAKE_KEY:}) and a shell reference ({@code EVENT_KEY="$(...)"}, {@code $HOME/...}).
     */
    private static boolean weakNameCounts(String text, String name, Matcher matcher, boolean arrow) {
        String separator = matcher.group(3);
        if (separator.indexOf('\n') >= 0 || separator.indexOf('\r') >= 0) {

            return false;
        }
        if (arrow) {

            return true;
        }
        if (!isEnvironmentName(name) || shellExpansion(text, matcher.end())) {

            return false;
        }

        return !matcher.group(1).isEmpty()
                || separator.indexOf('=') < 0
                || !Character.isWhitespace(separator.charAt(0));
    }

    /**
     * Whether a value (after an optional plain or escaped quote) reads as a shell reference rather than
     * a literal: $(command), ${NAME...}, or $NAME, $1 and $@ ending where the value, a word or a path
     * segment does. $2b$10$... (a bcrypt hash) and $argon2id$... are literals.
     */
    private static boolean shellExpansion(String text, int valueStart) {
        int at = valueStart + escapedQuoteLength(text, valueStart) + unicodeQuoteLength(text, valueStart);
        if (at == valueStart && at < text.length() && (text.charAt(at) == '"' || text.charAt(at) == '\'')) {
            at++;
        }
        if (at + 1 >= text.length() || text.charAt(at) != '$') {

            return false;
        }
        char first = text.charAt(at + 1);
        if (first == '(' || first == '{') {

            return true;
        }
        int end;
        if (isAsciiDigit(first) || "@*#?".indexOf(first) >= 0) {
            end = at + 2;
        } else if (first == '_' || first < 128 && Character.isLetter(first)) {
            end = at + 2;
            while (end < text.length() && isWordChar(text.charAt(end))) {
                end++;
            }
        } else {

            return false;
        }

        return end == text.length() || !isWordChar(text.charAt(end)) && text.charAt(end) != '$';
    }

    private static boolean isEnvironmentName(String name) {

        return name.chars().noneMatch(Character::isLowerCase);
    }

    // Where a key begins: its quote, or the character after a JSON escape letter glued to it.
    private static int keyStart(String text, Matcher matcher) {

        return keyName(text, matcher).length() == matcher.group(2).length() ? matcher.start() : matcher.start(2) + 1;
    }

    /**
     * Where a name stands on its displayed line. {@code level} is the number of backslashes in a
     * JSON-escaped line break at the line's encoding level (0 in raw text), or -1 when other text
     * precedes the name on its line; {@code tabsOnly} is set when only tabs indent it, as in
     * launchctl print; {@code closer} closes the code span or bold text the name opens, else null.
     */
    private record LineStart(int level, boolean tabsOnly, String closer) {
        static final LineStart NONE = new LineStart(-1, false, null);

        boolean starts() {

            return level >= 0;
        }
    }

    /**
     * Whether a name starts its displayed line: a raw or JSON-escaped line break, the start of the
     * text, or the quote that opens a JSON string, followed only by indentation (spaces, tabs,
     * JSON-escaped tabs), optionally after a line-number or path gutter (cat -n, grep -n, rg, a file
     * view), quote marks, a Markdown heading, a list or diff marker or an opening code span or bold
     * marker, and by the dotted prefix of the name itself ({@code sba.judge.password}). A name inline
     * in prose ({@code see `SBA_X_TOKEN => …`}) does not.
     */
    private static LineStart lineStart(String text, int nameStart) {
        int at = nameStart;
        while (at > 1 && text.charAt(at - 1) == '.' && nameStart - at < 128) {
            int segment = at - 1;
            while (segment > 0 && isNameChar(text.charAt(segment - 1)) && !escapeLetter(text, segment - 1)) {
                if (nameStart - segment >= MAX_LINE_PREFIX) {

                    // Each lookup reads a bounded stretch before the name.
                    return LineStart.NONE;
                }
                segment--;
            }
            if (segment == at - 1) {
                break;
            }
            at = segment;
        }
        int lineStart = at;
        int level = -1;
        while (level < 0) {
            if (lineStart == 0) {
                level = 0;
                break;
            }
            if (at - lineStart > MAX_LINE_PREFIX) {

                return LineStart.NONE;
            }
            char previous = text.charAt(lineStart - 1);
            if (previous == '\n' || previous == '\r') {
                level = 0;
            } else if (previous == '"') {
                // The quote that opens the JSON string holding this line: its contents are one level deeper.
                level = backslashRunBefore(text, lineStart - 1) + 1;
            } else if ((previous == 'n' || previous == 'r') && backslashRunBefore(text, lineStart - 1) > 0) {
                level = backslashRunBefore(text, lineStart - 1);
            } else {
                lineStart--;
            }
        }
        boolean escaped = level > 0;
        int tabLevel = 0;
        StringBuilder prefix = new StringBuilder(at - lineStart);
        for (int index = lineStart; index < at; index++) {
            char current = text.charAt(index);
            int run = current == '\\' ? backslashRun(text, index) : 0;
            if (run > 0 && index + run < at && text.charAt(index + run) == 't') {
                tabLevel = tabLevel == 0 ? run : tabLevel;
                prefix.append('\t');
                index += run;
            } else if (run > 0 && text.startsWith("u2192", index + run) && index + run + 5 <= at) {
                prefix.append('→');
                index += run + 4;
            } else if (run > 0) {
                // Backslashes that escape nothing shown here read as themselves, the whole run at once.
                int end = Math.min(index + run, at);
                prefix.append(text, index, end);
                index = end - 1;
            } else {
                prefix.append(current);
            }
        }
        Matcher gutter = LINE_PREFIX.matcher(prefix);
        if (!gutter.matches()) {

            return LineStart.NONE;
        }
        boolean tabsOnly = !prefix.isEmpty() && prefix.chars().allMatch(character -> character == '\t');
        if (!escaped && tabLevel == 0 && lineStart == 0) {
            // The first line of the text: its own line break, raw or escaped, shows its encoding level.
            tabLevel = firstLineBreakRun(text, nameStart);
        }

        return new LineStart(escaped ? level : tabLevel, tabsOnly, gutter.group(1));
    }

    private static boolean isNameChar(char value) {

        return isWordChar(value) || value == '-';
    }

    // The backslashes in the first line break after this index when it is JSON-escaped, else 0.
    private static int firstLineBreakRun(String text, int from) {
        for (int at = from; at < text.length(); at++) {
            char current = text.charAt(at);
            if (current == '\n' || current == '\r') {

                return 0;
            }
            if (current == '\\') {
                int run = backslashRun(text, at);
                if (at + run < text.length() && "nr".indexOf(text.charAt(at + run)) >= 0) {

                    return run;
                }
                at += run;
            }
        }

        return 0;
    }

    // Whether the character here is the letter of a JSON escape (the t of \t).
    private static boolean escapeLetter(String text, int at) {

        return at > 0 && text.charAt(at - 1) == '\\' && "nrt".indexOf(text.charAt(at)) >= 0;
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
     * Used when the key does not start its line.
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

    private static AssignmentValue assignmentValue(
            String text, int start, int level, boolean wholeLine, boolean lastQuote, boolean continues, String closer) {
        char quote = text.charAt(start);
        int escapedQuote = escapedQuoteLength(text, start);
        if (lastQuote && (quote == '"' || escapedQuote > 0)) {
            // plutil -p prints a string value between quotes without escaping the quotes inside it:
            // the value closes at the last quote on its line.
            String delimiter = escapedQuote > 0 ? text.substring(start, start + escapedQuote) : "\"";
            int stop = lineEnd(text, start + delimiter.length(), level, false);
            int end = lastQuoteBefore(text, start + delimiter.length(), stop, delimiter.length() - 1);
            if (end > 0) {

                return new AssignmentValue(attachedSuffixEnd(text, end), delimiter + REDACTED + delimiter);
            }
        }
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
        if (escapedQuote > 0) {
            // A value quoted inside JSON-encoded text ends at the same escaped quote. Quotes
            // escaped one level deeper belong to the value; a missing close consumes the rest.
            String delimiter = text.substring(start, start + escapedQuote);
            int end = escapedQuoteEnd(text, start + escapedQuote, escapedQuote - 1);

            return end < 0
                    ? new AssignmentValue(text.length(), delimiter + REDACTED)
                    : new AssignmentValue(attachedSuffixEnd(text, end), delimiter + REDACTED + delimiter);
        }
        int unicodeQuote = unicodeQuoteLength(text, start);
        if (unicodeQuote > 0) {
            // A value between unicode-escaped quotes (\\u0022...\\u0022) ends at the same escape.
            String delimiter = text.substring(start, start + unicodeQuote);
            int end = unicodeQuoteEnd(text, start + unicodeQuote, delimiter);

            return end < 0
                    ? new AssignmentValue(text.length(), delimiter + REDACTED)
                    : new AssignmentValue(attachedSuffixEnd(text, end), delimiter + REDACTED + delimiter);
        }
        if (text.startsWith("{length = ", start)) {

            // A plutil -p data value: {length = 16, bytes = 0x0123...}.
            return new AssignmentValue(lineEnd(text, start, level, true), REDACTED);
        }
        if (wholeLine) {
            int end = lineEnd(text, start, level, false);
            int closed = closer == null ? -1 : text.indexOf(closer, start, end);
            if (closed >= 0) {

                // A code span or bold text that opened before the name ends the value: `NAME => value`.
                return new AssignmentValue(closed, REDACTED);
            }

            return new AssignmentValue(continues ? continuationEnd(text, end, level) : end, REDACTED);
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

    // The end of the last quote in [from, stop) preceded by exactly this many backslashes, else -1.
    private static int lastQuoteBefore(String text, int from, int stop, int slashes) {
        for (int quote = text.lastIndexOf('"', stop - 1); quote >= from; quote = text.lastIndexOf('"', quote - 1)) {
            if (backslashRunBefore(text, quote) == slashes && quote - slashes >= from) {

                return quote + 1;
            }
        }

        return -1;
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
     * The end of a displayed line from start: a raw line break or, in JSON-escaped text displayed at
     * this level ({@code level} backslashes in its line break), an escaped line break or the quote
     * that closes the string holding the display. An escaped quote or backslash of the display itself
     * belongs to the line. With {@code throughBrace}, the first {@code }} on the line ends it too,
     * included.
     */
    private static int lineEnd(String text, int start, int level, boolean throughBrace) {
        int end = start;
        while (end < text.length()) {
            char current = text.charAt(end);
            if (current == '\n' || current == '\r' || current == '"' && level > 0) {

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
            if (end + run >= text.length()) {

                return text.length();
            }
            char escaped = text.charAt(end + run);
            if (escaped == '\n' || escaped == '\r') {

                // Backslashes that end a raw line, as in a shell continuation, belong to it.
                return end + run;
            }
            int offset = level > 0 ? lineBreakOffset(run, escaped, level) : -1;
            if (offset >= 0) {

                return end + offset;
            }
            // The escaped character belongs to the line, even when it is a quote.
            end += run + 1;
        }

        return end;
    }

    /**
     * Where a run of backslashes before this character ends a line displayed at this encoding level,
     * as an offset into the run, else -1. At level {@code l} (backslashes in its line break), a line
     * break of the display carries {@code l} backslashes modulo {@code 2l}, the quote that closes its
     * string {@code l - 1}, a quote of the display {@code 2l - 1} and a literal backslash {@code 2l};
     * literal backslashes before the break belong to the line. Any other run before a line break or
     * quote belongs to an enclosing level and ends the line where the run starts.
     */
    private static int lineBreakOffset(int run, char next, int level) {
        int period = 2 * level;
        int rest = run % period;
        if (next == 'n' || next == 'r') {

            return rest == 0 ? -1 : rest == level ? run - level : 0;
        }
        if (next != '"' || rest == period - 1) {

            return -1;
        }

        return rest == level - 1 ? run - rest : 0;
    }

    /**
     * Where a launchctl print value that holds line breaks ends: its entries are indented with a tab,
     * so the lines after an entry that are not are the rest of its value. They are taken only when a
     * tab-indented line follows them; a blank line or the end of the text first means the display
     * ended and other text follows.
     */
    private static int continuationEnd(String text, int lineEnd, int level) {
        int end = lineEnd;
        while (end - lineEnd <= MAX_CONTINUATION_CHARS) {
            int next = afterLineBreak(text, end, level);
            if (next < 0 || next >= text.length() || atLineBreak(text, next, level)) {

                return lineEnd;
            }
            if (startsWithTab(text, next)) {

                return end;
            }
            end = lineEnd(text, next, level, false);
        }

        return lineEnd;
    }

    // The index after the raw or escaped line break at this index, else -1.
    private static int afterLineBreak(String text, int at, int level) {
        if (at >= text.length()) {

            return -1;
        }
        char current = text.charAt(at);
        if (current == '\r') {

            return at + 1 < text.length() && text.charAt(at + 1) == '\n' ? at + 2 : at + 1;
        }
        if (current == '\n') {

            return at + 1;
        }
        int run = current == '\\' ? backslashRun(text, at) : 0;
        if (level == 0 || run == 0 || at + run >= text.length() || "nr".indexOf(text.charAt(at + run)) < 0) {

            return -1;
        }
        int after = at + run + 1;
        if (text.charAt(at + run) == 'r' && text.startsWith("n", after + run) && backslashRun(text, after) == run) {
            after += run + 1;
        }

        return after;
    }

    private static boolean atLineBreak(String text, int at, int level) {
        char current = text.charAt(at);
        if (current == '\n' || current == '\r' || current == '"' && level > 0) {

            return true;
        }
        int run = current == '\\' ? backslashRun(text, at) : 0;

        return level > 0
                && run > 0
                && at + run < text.length()
                && lineBreakOffset(run, text.charAt(at + run), level) == 0;
    }

    private static boolean startsWithTab(String text, int at) {
        if (text.charAt(at) == '\t') {

            return true;
        }
        int run = text.charAt(at) == '\\' ? backslashRun(text, at) : 0;

        return run > 0 && at + run < text.length() && text.charAt(at + run) == 't';
    }

    // Characters glued to a closing quote, as in the shell word "abc"def, continue the value;
    // a delimiter, quote or JSON escape after the quote ends it.
    private static int attachedSuffixEnd(String text, int closedAt) {
        if (closedAt == text.length()) {

            return closedAt;
        }
        char next = text.charAt(closedAt);
        // A JSON escape at any encoding level (\n, \\\", ...), such as the quote closing the string
        // that holds this value.
        int run = next == '\\' ? backslashRun(text, closedAt) : 0;
        boolean escape =
                run > 0 && closedAt + run < text.length() && "nrt\"'".indexOf(text.charAt(closedAt + run)) >= 0;
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

    // The length of a unicode-escaped quote (one to fifteen backslashes, then u0022 or u0027) at start,
    // else 0.
    private static int unicodeQuoteLength(String text, int start) {
        int slashes = 0;
        while (slashes < MAX_ESCAPE_RUN && start + slashes < text.length() && text.charAt(start + slashes) == '\\') {
            slashes++;
        }
        boolean quote =
                slashes > 0 && (text.startsWith("u0022", start + slashes) || text.startsWith("u0027", start + slashes));

        return quote ? slashes + 5 : 0;
    }

    // The end of the next delimiter not preceded by another backslash, else -1.
    private static int unicodeQuoteEnd(String text, int from, String delimiter) {
        for (int at = text.indexOf(delimiter, from); at >= 0; at = text.indexOf(delimiter, at + 1)) {
            if (at == from || text.charAt(at - 1) != '\\') {

                return at + delimiter.length();
            }
        }

        return -1;
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
     * Ruby), an environment-style name without lowercase letters (launchctl print), a compact
     * {@code key=>value}, which stays conservative, or a name in any letter case that starts its
     * displayed line, as launchctl print shows {@code JevApiKey => …}, unless the value is an
     * arrow-function body that uses the parameter or opens a block.
     */
    private static boolean arrowPair(String text, Matcher matcher, int valueStart, String name, LineStart line) {
        char first = text.charAt(valueStart);
        String separator = matcher.group(3);
        if (!matcher.group(1).isEmpty()
                || first == '"'
                || first == '\''
                || escapedQuoteLength(text, valueStart) > 0
                || unicodeQuoteLength(text, valueStart) > 0
                || !Character.isWhitespace(separator.charAt(separator.length() - 1))
                || isEnvironmentName(name)) {

            return true;
        }

        return line.starts() && !arrowFunctionBody(text, valueStart, name);
    }

    // A block or parenthesized body, or an expression that uses the parameter's own name on the same
    // line: token => token.trim(), password => check(password).
    private static boolean arrowFunctionBody(String text, int valueStart, String name) {
        char first = text.charAt(valueStart);
        if (first == '{' || first == '(') {

            return true;
        }
        int stop = valueStart;
        int limit = Math.min(text.length(), valueStart + MAX_ARROW_BODY_CHARS);
        while (stop < limit
                && text.charAt(stop) != '\n'
                && text.charAt(stop) != '\r'
                && !(text.charAt(stop) == '\\' && stop + 1 < limit && "nr".indexOf(text.charAt(stop + 1)) >= 0)) {
            stop++;
        }
        for (int at = text.indexOf(name, valueStart, stop); at >= 0; at = text.indexOf(name, at + 1, stop)) {
            int after = at + name.length();
            if ((at == 0 || !isIdentifierChar(text.charAt(at - 1)))
                    && (after >= text.length() || !isIdentifierChar(text.charAt(after)))) {

                return true;
            }
        }

        return false;
    }

    private static boolean isIdentifierChar(char value) {

        return isWordChar(value) || value == '$';
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
     * Replaces the argument of each credential flag: {@code --api-key VALUE} on a command line, the
     * next element of a quoted list ({@code ["--api-key", "VALUE"]}, plutil -p, defaults read), or
     * the next line when the flag is alone on its line (launchctl print, PlistBuddy). The
     * {@code --api-key=VALUE} form is an assignment.
     */
    private static String redactSecretFlags(String text) {
        Matcher matcher = FLAG.matcher(text);
        StringBuilder redacted = null;
        int copiedThrough = 0;
        int searchFrom = 0;
        while (searchFrom < text.length() && matcher.find(searchFrom)) {
            searchFrom = matcher.end();
            if (!isSecretFlag(matcher.group())) {
                continue;
            }
            Span value = flagValue(text, matcher.start(), matcher.end());
            if (value == null) {
                continue;
            }
            if (redacted == null) {
                redacted = new StringBuilder(text.length());
            }
            redacted.append(text, copiedThrough, value.start()).append(value.replacement());
            copiedThrough = value.end();
            searchFrom = value.end();
        }

        return redacted == null
                ? text
                : redacted.append(text, copiedThrough, text.length()).toString();
    }

    // The argument of the flag in [flagStart, flagEnd), or null when none follows.
    private static Span flagValue(String text, int flagStart, int flagEnd) {
        if (flagEnd >= text.length()) {

            return null;
        }
        String delimiter = quoteBefore(text, flagStart);
        if (!delimiter.isEmpty() && text.startsWith(delimiter, flagEnd)) {

            return listElementAfter(text, flagEnd + delimiter.length(), delimiter);
        }
        int at = flagEnd;
        while (at < text.length() && (text.charAt(at) == ' ' || text.charAt(at) == '\t')) {
            at++;
        }
        if (at == text.length() || "\n\r\\\"".indexOf(text.charAt(at)) >= 0) {
            // Alone on its line, the flag takes the next line, as launchctl print and PlistBuddy show.
            LineStart line = lineStart(text, flagStart);
            if (line.starts() && (at == text.length() || atLineBreak(text, at, line.level()))) {

                return nextLineAfter(text, at, line.level());
            }
        }
        if (at == flagEnd || at == text.length() || !startsArgument(text, at) || proseWord(text, at)) {

            return null;
        }

        return shellWord(text, at);
    }

    // Prose names a flag too ("pass --api-key or --token to the CLI"): an English function word after
    // it is not its argument.
    private static boolean proseWord(String text, int at) {
        int end = at;
        while (end < text.length() && end - at <= 4 && text.charAt(end) >= 'a' && text.charAt(end) <= 'z') {
            end++;
        }
        boolean wordEnds = end > at && (end == text.length() || !isWordChar(text.charAt(end)));

        return wordEnds && PROSE_WORDS.contains(text.substring(at, end));
    }

    // The quote that opens the list element holding a flag at this index ("--api-key" or \"--api-key),
    // else "".
    private static String quoteBefore(String text, int flagStart) {
        if (flagStart == 0) {

            return "";
        }
        char quote = text.charAt(flagStart - 1);
        if (quote == '\'') {

            return "'";
        }
        if (quote != '"') {

            return "";
        }

        return "\\".repeat(Math.min(backslashRunBefore(text, flagStart - 1), MAX_ESCAPE_RUN)) + "\"";
    }

    // The list element after a flag element that closed at from, quoted the same way: separators, line
    // breaks, indentation and a plutil -p index (2 => ) may stand between them. An unquoted element,
    // such as a variable in code ("--api-key", key), is not taken.
    private static Span listElementAfter(String text, int from, String delimiter) {
        int at = from;
        int limit = Math.min(text.length(), from + MAX_LINE_PREFIX);
        boolean indexed = false;
        while (at < limit) {
            char current = text.charAt(at);
            int run = current == '\\' ? backslashRun(text, at) : 0;
            if (current == ' ' || current == '\t' || current == ',' || current == '\n' || current == '\r') {
                at++;
            } else if (run > 0
                    && run < delimiter.length()
                    && at + run < text.length()
                    && "nrt".indexOf(text.charAt(at + run)) >= 0) {
                at += run + 1;
            } else if (!indexed && isAsciiDigit(current)) {
                int digits = at;
                while (digits < limit && isAsciiDigit(text.charAt(digits))) {
                    digits++;
                }
                int arrow = digits;
                while (arrow < limit && text.charAt(arrow) == ' ') {
                    arrow++;
                }
                if (!text.startsWith("=>", arrow)) {

                    return null;
                }
                indexed = true;
                at = arrow + 2;
            } else {
                break;
            }
        }
        if (at >= limit) {

            return null;
        }
        if (text.startsWith(delimiter, at)) {
            int contentStart = at + delimiter.length();
            if (startsFlag(text, contentStart)) {

                return null;
            }
            int end = delimiter.length() == 1
                    ? quotedEnd(text, contentStart, delimiter.charAt(0))
                    : escapedQuoteEnd(text, contentStart, delimiter.length() - 1);

            return end < 0
                    ? new Span(at, text.length(), delimiter + REDACTED)
                    : new Span(at, end, delimiter + REDACTED + delimiter);
        }

        return null;
    }

    // The end of a quoted value whose content starts here, with backslash escapes, else -1.
    private static int quotedEnd(String text, int contentStart, char quote) {
        int end = contentStart;
        while (end < text.length()) {
            char current = text.charAt(end++);
            if (current == '\\' && end < text.length()) {
                end++;
            } else if (current == quote) {

                return end;
            }
        }

        return -1;
    }

    // The line after the line break at this index, after its indentation, through its end; null when
    // it is blank, holds another flag, or no line follows.
    private static Span nextLineAfter(String text, int lineBreak, int level) {
        int at = afterLineBreak(text, lineBreak, level);
        if (at < 0) {

            return null;
        }
        while (at < text.length()) {
            char current = text.charAt(at);
            int run = current == '\\' ? backslashRun(text, at) : 0;
            if (current == ' ' || current == '\t') {
                at++;
            } else if (run > 0 && at + run < text.length() && text.charAt(at + run) == 't') {
                at += run + 1;
            } else {
                break;
            }
        }
        if (at >= text.length() || atLineBreak(text, at, level) || startsFlag(text, at)) {

            return null;
        }

        return new Span(at, lineEnd(text, at, level, false), REDACTED);
    }

    // Whether an argument starts here rather than another flag, a shell operator or a comment.
    private static boolean startsArgument(String text, int at) {
        char first = text.charAt(at);

        return "-<>|&;)#`]}".indexOf(first) < 0 && !Character.isWhitespace(first);
    }

    // A shell word at this index: a quoted string (plain or JSON-escaped) or a bare word.
    private static Span shellWord(String text, int at) {
        AssignmentValue value = assignmentValue(text, at, 0, false, false, false, null);

        return value.end() > at ? new Span(at, value.end(), value.replacement()) : null;
    }

    /**
     * Replaces the value that a configuration write command gives a credential name: launchctl setenv,
     * PlistBuddy Set and Add (through the end of the -c argument), plutil -replace and -insert,
     * defaults write, and the -w password of security add-generic-password.
     */
    private static String redactWriteCommands(String text) {
        Matcher matcher = WRITE_COMMAND.matcher(text);
        StringBuilder redacted = null;
        int copiedThrough = 0;
        int searchFrom = 0;
        while (searchFrom < text.length() && matcher.find(searchFrom)) {
            searchFrom = matcher.end();
            if (matcher.end() == text.length()) {
                break;
            }
            String name = writtenName(matcher);
            if (name != null && (!isSecretKey(name) || countsTokens(name))) {
                continue;
            }
            Span value = matcher.group("buddy") != null
                    ? buddyValue(text, matcher.start(), matcher.end())
                    : startsArgument(text, matcher.end()) ? shellWord(text, matcher.end()) : null;
            if (value == null) {
                continue;
            }
            if (redacted == null) {
                redacted = new StringBuilder(text.length());
            }
            redacted.append(text, copiedThrough, value.start()).append(value.replacement());
            copiedThrough = value.end();
            searchFrom = value.end();
        }

        return redacted == null
                ? text
                : redacted.append(text, copiedThrough, text.length()).toString();
    }

    // The name a write command assigns (the last segment of a key path), or null for a password.
    private static String writtenName(Matcher matcher) {
        if (matcher.group("setenv") != null) {

            return matcher.group("setenv");
        }
        if (matcher.group("buddy") != null) {
            String path = matcher.group("buddy");

            return path.substring(path.lastIndexOf(':') + 1);
        }
        if (matcher.group("plutil") != null) {
            String path = matcher.group("plutil");

            return path.substring(path.lastIndexOf('.') + 1);
        }

        return matcher.group("defaults");
    }

    // A PlistBuddy value runs to the end of its command: the quote that opened the -c argument, else
    // the end of the line, raw or JSON-escaped.
    private static Span buddyValue(String text, int commandStart, int valueStart) {
        String delimiter = quoteBefore(text, commandStart);
        int end;
        if (delimiter.isEmpty()) {
            end = bareLineEnd(text, valueStart, lineEnd(text, valueStart, 0, false));
        } else if (delimiter.length() == 1) {
            end = quotedEnd(text, valueStart, delimiter.charAt(0));
            end = end < 0 ? text.length() : end - 1;
        } else {
            end = escapedQuoteEnd(text, valueStart, delimiter.length() - 1);
            end = end < 0 ? text.length() : end - delimiter.length();
        }

        return end > valueStart ? new Span(valueStart, end, REDACTED) : null;
    }

    // The first JSON-escaped line break in [from, stop), else stop.
    private static int bareLineEnd(String text, int from, int stop) {
        for (int at = from; at < stop; at++) {
            if (text.charAt(at) == '\\') {
                int run = backslashRun(text, at);
                if (at + run < stop && "nr".indexOf(text.charAt(at + run)) >= 0) {

                    return at;
                }
                at += run;
            }
        }

        return stop;
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

    private record Span(int start, int end, String replacement) {}

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
