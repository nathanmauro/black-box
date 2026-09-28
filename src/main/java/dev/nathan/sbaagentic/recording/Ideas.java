package dev.nathan.sbaagentic.recording;

import java.util.List;
import java.util.Locale;

/**
 * The one vocabulary for the {@code Idea} capture kind, shared by capture validation and the
 * readers that list or migrate ideas, so the allowed values and the default identity never drift.
 */
public final class Ideas {

    public static final String EVENT_TYPE = "Idea";
    public static final String KIND = "idea";
    public static final String TEXT_PREFIX = "[Idea]";

    public static final String ORIGIN_HUMAN_ASIDE = "human-aside";
    public static final String ORIGIN_AGENT_PROPOSED = "agent-proposed";
    public static final String ORIGIN_JOINT = "joint";
    public static final List<String> ORIGINS = List.of(ORIGIN_HUMAN_ASIDE, ORIGIN_AGENT_PROPOSED, ORIGIN_JOINT);

    public static final String STATUS_UNTOUCHED = "untouched";
    public static final List<String> STATUSES =
            List.of(STATUS_UNTOUCHED, "partially-built", "built-unused", "superseded", "tracked");

    public static final int MIN_LEGS = 0;
    public static final int MAX_LEGS = 10;

    private static final String LEGACY_HUMAN_ASIDE = "nathan-aside";
    private static final int MAX_KEY_LENGTH = 160;

    private Ideas() {}

    /**
     * Returns the canonical origin, or {@code null} when the value is not an allowed origin. Case,
     * surrounding whitespace, and {@code _}/space separators are forgiven; the legacy
     * {@code nathan-aside} normalizes to {@code human-aside}.
     */
    public static String normalizeOrigin(String origin) {
        String token = token(origin);
        if (token == null) {

            return null;
        }
        if (LEGACY_HUMAN_ASIDE.equals(token)) {

            return ORIGIN_HUMAN_ASIDE;
        }

        return ORIGINS.contains(token) ? token : null;
    }

    /** Returns the canonical status, or {@code null} when the value is not an allowed status. */
    public static String normalizeStatus(String status) {
        String token = token(status);

        return token != null && STATUSES.contains(token) ? token : null;
    }

    /**
     * Default stable identity: a slug of the repo's last path segment plus the title. The basename
     * (not the full path) keeps the key identical across machines whose checkouts live at different
     * absolute paths; two repos with the same basename share a key namespace.
     */
    public static String defaultKey(String repo, String title) {
        String base = repoBasename(repo);
        String raw = base == null ? nullToEmpty(title) : base + " " + nullToEmpty(title);
        String slug = slug(raw);
        if (slug.length() > MAX_KEY_LENGTH) {
            slug = slug.substring(0, MAX_KEY_LENGTH).replaceAll("-+$", "");
        }

        return slug.isEmpty() ? "idea" : slug;
    }

    /** Lowercase, every run of non-alphanumerics becomes one {@code -}, and the ends are trimmed. */
    public static String slug(String value) {
        if (value == null) {

            return "";
        }

        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
    }

    private static String repoBasename(String repo) {
        if (repo == null || repo.isBlank()) {

            return null;
        }
        String trimmed = repo.strip().replaceAll("[/\\\\]+$", "");
        int slash = Math.max(trimmed.lastIndexOf('/'), trimmed.lastIndexOf('\\'));
        String base = slash >= 0 ? trimmed.substring(slash + 1) : trimmed;

        return base.isBlank() ? null : base;
    }

    private static String token(String value) {
        if (value == null || value.isBlank()) {

            return null;
        }

        return value.strip().toLowerCase(Locale.ROOT).replaceAll("[\\s_]+", "-");
    }

    private static String nullToEmpty(String value) {

        return value == null ? "" : value;
    }
}
