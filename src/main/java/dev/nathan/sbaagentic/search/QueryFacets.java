package dev.nathan.sbaagentic.search;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses a search query string into structured keyword facets plus free text, so the SQLite search
 * path can honour {@code field:value} filters (e.g. {@code source:codex kind:Decision}) even when the
 * optional Elasticsearch index is off.
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
    private final List<String> freeText;

    private QueryFacets(String source, String eventType, String toolName, String cwd, List<String> freeText) {
        this.source = source;
        this.eventType = eventType;
        this.toolName = toolName;
        this.cwd = cwd;
        this.freeText = List.copyOf(freeText);
    }

    public static QueryFacets parse(String query) {
        String source = null;
        String eventType = null;
        String toolName = null;
        String cwd = null;
        List<String> free = new ArrayList<>();

        for (String token : tokenize(query == null ? "" : query)) {
            Matcher m = FACET.matcher(token);
            if (m.matches()) {
                String field = m.group(1).toLowerCase(Locale.ROOT);
                String value = stripQuotes(m.group(2)).trim();
                if (value.isEmpty()) {
                    continue;
                }
                switch (field) {
                    case "source", "agent" -> source = value;
                    case "kind", "event_type" -> eventType = value;
                    case "tool", "tool_name" -> toolName = value;
                    case "project", "cwd" -> cwd = value;
                    default -> { /* unreachable: regex restricts the field set */ }
                }
            } else {
                String term = stripQuotes(token).trim();
                if (!term.isEmpty()) {
                    free.add(term);
                }
            }
        }
        return new QueryFacets(source, eventType, toolName, cwd, free);
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
        return source != null || eventType != null || toolName != null || cwd != null;
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

    public List<String> freeText() {
        return freeText;
    }

    /** The free-text terms rejoined into a single phrase (empty string when there is none). */
    public String freeTextPhrase() {
        return String.join(" ", freeText);
    }
}
