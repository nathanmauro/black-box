package dev.nathan.sbaagentic.project;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Immutable canonical project identity with stable URL and display representations. */
public record ProjectKey(String value) {

    public static final String NO_PROJECT = "__no_project__";
    public static final String NO_PROJECT_LABEL = "No project / manual / system";

    public ProjectKey {
        value = canonicalize(value);
    }

    public static ProjectKey of(String cwd) {
        return new ProjectKey(cwd);
    }

    public static ProjectKey decode(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            throw new IllegalArgumentException("Project key is required.");
        }
        try {
            return new ProjectKey(new String(
                    Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8));
        }
        catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Invalid project key.", ex);
        }
    }

    public String encoded() {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    public String label() {
        if (NO_PROJECT.equals(value)) {
            return NO_PROJECT_LABEL;
        }
        if (value.startsWith("/Users/")) {
            int nextSlash = value.indexOf('/', "/Users/".length());
            if (nextSlash > 0) {
                return "~" + value.substring(nextSlash);
            }
        }
        if (value.startsWith("/home/")) {
            int nextSlash = value.indexOf('/', "/home/".length());
            if (nextSlash > 0) {
                return "~" + value.substring(nextSlash);
            }
        }
        return value;
    }

    private static String canonicalize(String cwd) {
        String canonical = cwd == null ? "" : cwd.trim();
        if (canonical.isEmpty()) {
            return NO_PROJECT;
        }
        while (canonical.length() > 1 && canonical.endsWith("/")) {
            canonical = canonical.substring(0, canonical.length() - 1);
        }
        return canonical;
    }
}
