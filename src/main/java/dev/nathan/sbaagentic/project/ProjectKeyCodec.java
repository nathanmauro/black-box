package dev.nathan.sbaagentic.project;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

final class ProjectKeyCodec {

    static final String NO_PROJECT_KEY = "__no_project__";
    static final String NO_PROJECT_LABEL = "No project / manual / system";

    private static final String HOME_PREFIX = "/Users/";
    private static final String LINUX_HOME_PREFIX = "/home/";

    private ProjectKeyCodec() {
    }

    static String canonicalize(String cwd) {
        String value = cwd == null ? "" : cwd.trim();
        if (value.isEmpty()) {
            return NO_PROJECT_KEY;
        }
        while (value.length() > 1 && value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    static String encode(String canonicalKey) {
        String value = canonicalize(canonicalKey);
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    static String decode(String projectKey) {
        if (projectKey == null || projectKey.isBlank()) {
            throw new IllegalArgumentException("Project key is required.");
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(projectKey), StandardCharsets.UTF_8);
            return canonicalize(decoded);
        }
        catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Invalid project key.", ex);
        }
    }

    static String labelFor(String canonicalKey) {
        String value = canonicalize(canonicalKey);
        if (NO_PROJECT_KEY.equals(value)) {
            return NO_PROJECT_LABEL;
        }
        if (value.startsWith(HOME_PREFIX)) {
            int nextSlash = value.indexOf('/', HOME_PREFIX.length());
            if (nextSlash > 0) {
                return "~" + value.substring(nextSlash);
            }
        }
        if (value.startsWith(LINUX_HOME_PREFIX)) {
            int nextSlash = value.indexOf('/', LINUX_HOME_PREFIX.length());
            if (nextSlash > 0) {
                return "~" + value.substring(nextSlash);
            }
        }
        return value;
    }
}
