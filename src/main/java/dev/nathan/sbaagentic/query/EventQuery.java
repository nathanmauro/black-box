package dev.nathan.sbaagentic.query;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The one grammar-v2 parser for Black Box query strings, shared by every backend surface (the
 * frontend keeps a TypeScript mirror pinned to the same {@code query/grammar-cases.json} fixture).
 * Pure — no database access, no framework types; SQL/ES compilation stays in the owning modules.
 *
 * <p>Facet tokens ({@code source:|agent:}, {@code kind:|event_type:}, {@code tool:|tool_name:},
 * {@code project:|cwd:}, {@code project_exact:|cwd_exact:}, {@code project_group:}) collect into
 * per-field include/exclude value lists: repeated tokens union and an unquoted comma inside one
 * value is an IN-list ({@code source:codex,claude}); quoting suppresses splitting
 * ({@code source:"a,b"} is one value). {@code -}/{@code NOT} negate the next facet. Operator
 * tokens add {@code session:} (session id or client session id), {@code since:}/{@code until:}
 * (ISO date, ISO datetime, {@code today}/{@code yesterday}, or {@code Nm/Nh/Nd/Nw} durations,
 * kept symbolic as {@link TimeSpec}s and resolved server-side at execution), {@code last:}
 * (duration-only sugar for {@code since:}), and {@code is:all} (disable the meaningful default).
 * Unrecognised operator values fall back to free text, and free text is per-term AND: each
 * whitespace-separated term (or quoted phrase) must match independently.
 */
public final class EventQuery {

    /** Canonical facet fields; each carries its UI aliases. */
    public enum Field {
        SOURCE("source", "agent"),
        KIND("kind", "event_type"),
        TOOL("tool", "tool_name"),
        PROJECT("project", "cwd"),
        PROJECT_EXACT("project_exact", "cwd_exact"),
        PROJECT_GROUP("project_group");

        private final List<String> aliases;

        Field(String... aliases) {
            this.aliases = List.of(aliases);
        }

        static Field byAlias(String name) {
            for (Field field : values()) {
                if (field.aliases.contains(name)) {
                    return field;
                }
            }
            return null;
        }
    }

    private static final Pattern PREFIXED_TOKEN = Pattern.compile("^([A-Za-z_]+):(.*)$");

    private final Map<Field, List<String>> values;
    private final Map<Field, List<String>> excluded;
    private final String sessionRef;
    private final TimeSpec sinceSpec;
    private final TimeSpec untilSpec;
    private final boolean includeAll;
    private final List<String> freeTerms;

    private EventQuery(
            Map<Field, List<String>> values,
            Map<Field, List<String>> excluded,
            String sessionRef,
            TimeSpec sinceSpec,
            TimeSpec untilSpec,
            boolean includeAll,
            List<String> freeTerms) {
        this.values = values;
        this.excluded = excluded;
        this.sessionRef = sessionRef;
        this.sinceSpec = sinceSpec;
        this.untilSpec = untilSpec;
        this.includeAll = includeAll;
        this.freeTerms = List.copyOf(freeTerms);
    }

    public static EventQuery parse(String query) {
        Map<Field, List<String>> values = new EnumMap<>(Field.class);
        Map<Field, List<String>> excluded = new EnumMap<>(Field.class);
        for (Field field : Field.values()) {
            values.put(field, new ArrayList<>());
            excluded.put(field, new ArrayList<>());
        }
        String sessionRef = null;
        TimeSpec sinceSpec = null;
        TimeSpec untilSpec = null;
        boolean includeAll = false;
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
            Matcher m = PREFIXED_TOKEN.matcher(candidate);
            if (m.matches()) {
                String name = m.group(1).toLowerCase(Locale.ROOT);
                String rawValue = m.group(2);
                Field field = Field.byAlias(name);
                // project_group is a hidden injected facet; a hand-typed negation of it is
                // meaningless, so it stays free text instead of a silently dropped exclusion.
                if (field == Field.PROJECT_GROUP && negated) {
                    field = null;
                }
                if (field != null) {
                    List<String> pieces = splitFacetValue(rawValue);
                    if (!pieces.isEmpty()) {
                        (negated ? excluded : values).get(field).addAll(pieces);
                        negateNext = false;
                        continue;
                    }
                }
                else if (!negated) {
                    String value = stripQuotes(rawValue).trim();
                    if (!value.isEmpty()) {
                        switch (name) {
                            case "session" -> {
                                sessionRef = value;
                                continue;
                            }
                            case "since" -> {
                                TimeSpec spec = TimeSpec.parse(value, TimeSpec.Edge.START);
                                if (spec != null) {
                                    sinceSpec = spec;
                                    continue;
                                }
                            }
                            case "until" -> {
                                TimeSpec spec = TimeSpec.parse(value, TimeSpec.Edge.END);
                                if (spec != null) {
                                    untilSpec = spec;
                                    continue;
                                }
                            }
                            case "last" -> {
                                TimeSpec spec = TimeSpec.parseDurationOnly(value, TimeSpec.Edge.START);
                                if (spec != null) {
                                    sinceSpec = spec;
                                    continue;
                                }
                            }
                            case "is" -> {
                                if ("all".equalsIgnoreCase(value)) {
                                    includeAll = true;
                                    continue;
                                }
                            }
                            default -> { /* unknown prefix: plain free text below */ }
                        }
                    }
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
        return new EventQuery(values, excluded, sessionRef, sinceSpec, untilSpec, includeAll, free);
    }

    /** Included values for one facet field, in query order; empty when the facet is unset. */
    public List<String> values(Field field) {
        return List.copyOf(values.get(field));
    }

    /** Excluded ({@code -}/{@code NOT}-negated) values for one facet field. */
    public List<String> excluded(Field field) {
        return List.copyOf(excluded.get(field));
    }

    /** The {@code session:} reference (session id or client session id); last one wins. */
    public Optional<String> sessionRef() {
        return Optional.ofNullable(sessionRef);
    }

    /** The lower time bound from {@code since:}/{@code last:}; last one wins. */
    public Optional<TimeSpec> sinceSpec() {
        return Optional.ofNullable(sinceSpec);
    }

    /** The upper time bound from {@code until:}; last one wins. */
    public Optional<TimeSpec> untilSpec() {
        return Optional.ofNullable(untilSpec);
    }

    /** True when {@code is:all} is present; overrides the wire {@code meaningful=true} param. */
    public boolean includeAll() {
        return includeAll;
    }

    /** Free-text terms; each is an independent AND-ed match (quoted phrases stay one term). */
    public List<String> freeTerms() {
        return freeTerms;
    }

    /** Hidden logical-project scopes ({@code project_group:}); alias for that field's values. */
    public List<String> projectGroups() {
        return values(Field.PROJECT_GROUP);
    }

    /** True when anything beyond free text was recognised (facets, session, time, {@code is:all}). */
    public boolean hasAnyFacet() {
        for (Field field : Field.values()) {
            if (!values.get(field).isEmpty() || !excluded.get(field).isEmpty()) {
                return true;
            }
        }
        return sessionRef != null || sinceSpec != null || untilSpec != null || includeAll;
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
            }
            else if (Character.isWhitespace(c) && !inQuotes) {
                if (current.length() > 0) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
            }
            else {
                current.append(c);
            }
        }
        if (current.length() > 0) {
            tokens.add(current.toString());
        }
        return tokens;
    }

    /**
     * Splits a facet value on commas outside double quotes, then unquotes and trims each piece —
     * quoted-ness must survive to this point so {@code source:"a,b"} stays one value while
     * {@code source:a,b} is an IN-list. Empty pieces are dropped.
     */
    static List<String> splitFacetValue(String rawValue) {
        List<String> pieces = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < rawValue.length(); i++) {
            char c = rawValue.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
                current.append(c);
            }
            else if (c == ',' && !inQuotes) {
                addPiece(pieces, current.toString());
                current.setLength(0);
            }
            else {
                current.append(c);
            }
        }
        addPiece(pieces, current.toString());
        return pieces;
    }

    private static void addPiece(List<String> pieces, String raw) {
        String piece = stripQuotes(raw).trim();
        if (!piece.isEmpty()) {
            pieces.add(piece);
        }
    }

    private static String stripQuotes(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }
}
