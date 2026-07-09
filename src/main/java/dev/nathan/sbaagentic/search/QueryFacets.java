package dev.nathan.sbaagentic.search;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses a search query string into structured keyword facets plus free text, so the SQLite search
 * path can honour {@code field:value} filters (e.g. {@code source:codex kind:Decision}) and negative
 * filters (e.g. {@code NOT kind:PostToolUse}) even when the optional Elasticsearch index is off.
 *
 * <p>UI field aliases map to physical columns: {@code source|agent}→source, {@code kind|event_type}→
 * event_type, {@code tool|tool_name}→tool_name, {@code project|cwd}→ session cwd. Recognised facet
 * tokens are pulled out (last one wins per field); everything else becomes free-text terms with any
 * surrounding double quotes stripped. Pure — no database access.
 */
public final class QueryFacets {

    private static final Pattern FACET = Pattern.compile(
            "^(source|agent|kind|event_type|tool|tool_name|project|cwd):(.+)$",
            Pattern.CASE_INSENSITIVE);

    private final String source;
    private final String eventType;
    private final String toolName;
    private final String cwd;
    private final String excludedSource;
    private final String excludedEventType;
    private final String excludedToolName;
    private final String excludedCwd;
    private final List<String> freeText;

    private QueryFacets(
            String source,
            String eventType,
            String toolName,
            String cwd,
            String excludedSource,
            String excludedEventType,
            String excludedToolName,
            String excludedCwd,
            List<String> freeText) {
        this.source = source;
        this.eventType = eventType;
        this.toolName = toolName;
        this.cwd = cwd;
        this.excludedSource = excludedSource;
        this.excludedEventType = excludedEventType;
        this.excludedToolName = excludedToolName;
        this.excludedCwd = excludedCwd;
        this.freeText = List.copyOf(freeText);
    }

    public static QueryFacets parse(String query) {
        String source = null;
        String eventType = null;
        String toolName = null;
        String cwd = null;
        String excludedSource = null;
        String excludedEventType = null;
        String excludedToolName = null;
        String excludedCwd = null;
        List<String> free = new ArrayList<>();
        boolean negateNext = false;

        for (String token : tokenize(query == null ? "" : query)) {
            if ("NOT".equalsIgnoreCase(token)) {
                if (negateNext) {
                    free.add("NOT");
                }
                negateNext = true;
                continue;
            }

            boolean leadingMinus = token.startsWith("-") && token.length() > 1;
            boolean negated = negateNext || leadingMinus;
            String candidate = leadingMinus ? token.substring(1) : token;
            Matcher m = FACET.matcher(candidate);
            if (m.matches()) {
                String field = m.group(1).toLowerCase(Locale.ROOT);
                String value = stripQuotes(m.group(2)).trim();
                if (!value.isEmpty()) {
                    switch (field) {
                        case "source", "agent" -> {
                            if (negated) {
                                excludedSource = value;
                            } else {
                                source = value;
                            }
                        }
                        case "kind", "event_type" -> {
                            if (negated) {
                                excludedEventType = value;
                            } else {
                                eventType = value;
                            }
                        }
                        case "tool", "tool_name" -> {
                            if (negated) {
                                excludedToolName = value;
                            } else {
                                toolName = value;
                            }
                        }
                        case "project", "cwd" -> {
                            if (negated) {
                                excludedCwd = value;
                            } else {
                                cwd = value;
                            }
                        }
                        default -> { /* unreachable: regex restricts the field set */ }
                    }
                    negateNext = false;
                    continue;
                }
            }

            if (negateNext) {
                free.add("NOT");
                negateNext = false;
            }
            String term = stripQuotes(token).trim();
            if (!term.isEmpty()) {
                free.add(term);
            }
        }
        if (negateNext) {
            free.add("NOT");
        }
        return new QueryFacets(source, eventType, toolName, cwd,
                excludedSource, excludedEventType, excludedToolName, excludedCwd, free);
    }

    /** Splits on whitespace but keeps double-quoted spans (incl. {@code field:"two words"}) together. */
    static List<String> tokenize(String query) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < query.length(); i++) {
            char c = query.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
                current.append(c);
            } else if (Character.isWhitespace(c) && !inQuotes) {
                if (current.length() > 0) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }
        if (current.length() > 0) {
            tokens.add(current.toString());
        }
        return tokens;
    }

    private static String stripQuotes(String value) {
        String v = value;
        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
            v = v.substring(1, v.length() - 1);
        }
        return v;
    }

    public boolean hasAnyFacet() {
        return source != null
                || eventType != null
                || toolName != null
                || cwd != null
                || excludedSource != null
                || excludedEventType != null
                || excludedToolName != null
                || excludedCwd != null;
    }

    public String source() {
        return source;
    }

    public String eventType() {
        return eventType;
    }

    public String toolName() {
        return toolName;
    }

    public String cwd() {
        return cwd;
    }

    public String excludedSource() {
        return excludedSource;
    }

    public String excludedEventType() {
        return excludedEventType;
    }

    public String excludedToolName() {
        return excludedToolName;
    }

    public String excludedCwd() {
        return excludedCwd;
    }

    public List<String> freeText() {
        return freeText;
    }

    /** The free-text terms rejoined into a single phrase (empty string when there is none). */
    public String freeTextPhrase() {
        return String.join(" ", freeText);
    }
}
