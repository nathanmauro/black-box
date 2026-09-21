package dev.nathan.sbaagentic.query;

import java.util.List;
import java.util.Locale;

/** Strict discovery diagnostics layered over, not changes to, the legacy grammar. */
public final class CompactQueryDiagnostics {
    private CompactQueryDiagnostics() { }

    public static List<String> validate(String query) {
        boolean negateNext = false;
        for (String token : EventQuery.tokenize(query)) {
            if (token.equalsIgnoreCase("NOT")) { negateNext = true; continue; }
            boolean negated = negateNext || token.startsWith("-");
            negateNext = false;
            // Quoting the whole token explicitly requests literal text.
            if (token.startsWith("\"")) continue;
            String raw = token.startsWith("-") ? token.substring(1) : token;
            int colon = raw.indexOf(':');
            if (colon < 0) continue;
            String name = raw.substring(0, colon).toLowerCase(Locale.ROOT);
            String value = raw.substring(colon + 1).replace("\"", "").trim();
            if (name.equals("before")) {
                return List.of("Unsupported before: operator. Use until:YYYY-MM-DD to include that entire day "
                        + "in the server timezone; exact until: instants are inclusive. Quote the whole token to search literally.");
            }
            if (List.of("since", "until", "last").contains(name)) {
                TimeSpec spec = name.equals("last") ? TimeSpec.parseDurationOnly(value, TimeSpec.Edge.START)
                        : TimeSpec.parse(value, name.equals("until") ? TimeSpec.Edge.END : TimeSpec.Edge.START);
                if (spec == null || negated) {
                    return List.of("Invalid time filter. Use since:/until: with an ISO date or timestamp, "
                            + "or last:7d. Negative time operators are unsupported. Quote the whole token for literal text.");
                }
            }
        }
        return List.of();
    }
}
