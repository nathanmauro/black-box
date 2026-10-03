package dev.nathan.sbaagentic.recording.internal.application;

import dev.nathan.sbaagentic.recording.ExportRedactor;
import dev.nathan.sbaagentic.recording.IngestionProperties;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class RedactionService implements ExportRedactor {

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
    // Bare or matching quoted keys, using the existing default assignment-name policy.
    private static final Pattern ASSIGNMENT = Pattern.compile("(?i)([\"']?)(" + SECRET_KEY + ")\\1(\\s*[=:]\\s*)");

    private final List<RedactionRule> exportRules = exportRules();

    private static List<RedactionRule> exportRules() {
        List<RedactionRule> result = new ArrayList<>(builtInRules());
        // JSON embedded in a text leaf may be escaped more than once. Conservatively
        // remove the rest of that leaf after a quoted credential key; do not guess
        // nested string boundaries and accidentally retain part of a secret.
        result.add(literal(SECRET_KEY + "\\\\*[\"']\\s*:\\s*.*", Pattern.CASE_INSENSITIVE | Pattern.DOTALL));

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

        return List.of(
                literal(
                        "-----BEGIN [^-\\r\\n]*PRIVATE KEY-----.*?-----END [^-\\r\\n]*PRIVATE KEY-----",
                        Pattern.CASE_INSENSITIVE | Pattern.DOTALL),
                literal("\\b(?:AKIA|ASIA|A3T[A-Z0-9])[A-Z0-9]{16}\\b", 0),
                RedactionService::redactAssignments,
                new RegexRule(
                        Pattern.compile("(?i)bearer\\s+[A-Za-z0-9._~+/=-]{16,}"), matcher -> "Bearer " + REDACTED),
                literal("\\bgh[pousr]_[A-Za-z0-9]{36,}\\b", 0),
                literal("\\bsk-[A-Za-z0-9_-]{20,}\\b", 0),
                literal("\\bxox[baprs]-[A-Za-z0-9-]{10,}\\b", 0));
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
            return new AssignmentValue(end, quote + REDACTED + (closed ? Character.toString(quote) : ""));
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

        return new AssignmentValue(end, prefix + REDACTED);
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
