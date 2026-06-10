package dev.nathan.sbaagentic.event;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.nathan.sbaagentic.config.SbaProperties;

import org.springframework.stereotype.Service;

@Service
public class RedactionService {

    private static final String REDACTED = "[REDACTED]";
    // Anchored at token starts via lookbehind, with a bounded lazy prefix and a possessive
    // suffix: greedy runs flanking the keyword alternation backtrack quadratically on long
    // keyword-dense inputs (measured seconds of CPU per event), this shape stays linear.
    private static final String SECRET_KEY =
            "(?<![A-Za-z0-9_-])[A-Za-z0-9_-]{0,40}?(?:api[_-]?key|secret[_-]?access[_-]?key|secret|token|passwd|password|client[_-]?secret|access[_-]?token|authorization)[A-Za-z0-9_-]*+";
    // Hard ceiling on what the regex engine ever sees: a single oversized scalar (huge tool
    // output, pasted log) must not pin a request thread. Anything past the cap is dropped
    // rather than stored unscanned.
    private static final int MAX_SCAN_CHARS = 50_000;
    private static final String CLIP_MARKER = " …[truncated]";

    private final boolean enabled;
    private final List<RedactionRule> rules;

    public RedactionService(SbaProperties properties) {
        this.enabled = properties.getIngestion().isRedactEnabled();
        List<String> customPatterns = properties.getIngestion().getRedactPatterns();
        this.rules = customPatterns.isEmpty() ? builtInRules() : customRules(customPatterns);
    }

    public String redact(String text) {
        if (!enabled || text == null) {
            return text;
        }
        String redacted = text.length() > MAX_SCAN_CHARS
                ? text.substring(0, MAX_SCAN_CHARS) + CLIP_MARKER
                : text;
        for (RedactionRule rule : rules) {
            redacted = rule.redact(redacted);
        }
        return redacted;
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
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                redacted.put(entry.getKey(), redactDeep(entry.getValue()));
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

    private static List<RedactionRule> builtInRules() {
        return List.of(
                literal("-----BEGIN [^-\\r\\n]*PRIVATE KEY-----.*?-----END [^-\\r\\n]*PRIVATE KEY-----",
                        Pattern.CASE_INSENSITIVE | Pattern.DOTALL),
                literal("\\b(?:AKIA|ASIA|A3T[A-Z0-9])[A-Z0-9]{16}\\b", 0),
                new RedactionRule(
                        Pattern.compile("(?i)(" + SECRET_KEY + ")(\\s*[=:]\\s*)(\"[^\"\\s]{8,}\"|'[^'\\s]{8,}'|[^\\s\"']{8,})"),
                        RedactionService::redactAssignment),
                new RedactionRule(
                        Pattern.compile("(?i)bearer\\s+[A-Za-z0-9._~+/=-]{16,}"),
                        matcher -> "Bearer " + REDACTED),
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
        return new RedactionRule(Pattern.compile(pattern, flags), matcher -> REDACTED);
    }

    private static String redactAssignment(Matcher matcher) {
        String value = matcher.group(3);
        if (value.startsWith("\"") || value.startsWith("'")) {
            String quote = value.substring(0, 1);
            return matcher.group(1) + matcher.group(2) + quote + REDACTED + quote;
        }
        return matcher.group(1) + matcher.group(2) + REDACTED;
    }

    private record RedactionRule(Pattern pattern, Replacement replacement) {

        String redact(String value) {
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
