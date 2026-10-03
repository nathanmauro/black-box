package dev.nathan.sbaagentic.recording;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Canonical typed links to stable ideas or event ids. */
public final class EvidenceRefs {
    private EvidenceRefs() {}

    public static String normalize(String raw, String field) {
        String value = raw == null ? null : raw.strip();
        if (value == null || value.isEmpty()) {
            throw invalid(field, raw);
        }
        int colon = value.indexOf(':');
        if (colon >= 0) {
            String prefix = value.substring(0, colon).toLowerCase(Locale.ROOT);
            String id = value.substring(colon + 1).strip();
            if (prefix.equals("idea") && !id.isEmpty()) {

                return "idea:" + id;
            }
            if (prefix.equals("event") && validEventId(id)) {

                return "event:" + id.toLowerCase(Locale.ROOT);
            }
            throw invalid(field, raw);
        }
        if (validEventId(value)) {

            return "event:" + value.toLowerCase(Locale.ROOT);
        }
        throw invalid(field, raw);
    }

    public static List<String> normalizeList(List<String> refs, String field) {
        if (refs == null || refs.isEmpty()) {

            return null;
        }
        if (refs.size() > 50) {
            throw new IllegalArgumentException(field + " may contain at most 50 refs.");
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String ref : refs) {
            unique.add(normalize(ref, field));
        }

        return List.copyOf(unique);
    }

    public static void checkDisjoint(List<String> supports, List<String> refutes) {
        if (supports == null || refutes == null) {

            return;
        }
        for (String ref : supports) {
            if (refutes.contains(ref)) {
                throw new IllegalArgumentException("A ref cannot appear in both supports and refutes: " + ref);
            }
        }
    }

    private static boolean validEventId(String id) {

        return id != null && id.toLowerCase(Locale.ROOT).matches("[0-9a-f-]{8,36}");
    }

    private static IllegalArgumentException invalid(String field, String value) {

        return new IllegalArgumentException(field + " entry '" + value
                + "' is not a valid ref; use idea:<ideaKey> or event:<eventId> (a bare event id is also accepted).");
    }
}
