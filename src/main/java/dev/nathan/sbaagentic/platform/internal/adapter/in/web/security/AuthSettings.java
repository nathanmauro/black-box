package dev.nathan.sbaagentic.platform.internal.adapter.in.web.security;

import org.springframework.core.env.Environment;

/** Deliberately has no generated toString: configuration contains credentials. */
final class AuthSettings {
    private final boolean enabled;
    private final String username;
    private final String password;
    private final String apiToken;
    private final boolean secureCookies;

    AuthSettings(Environment environment) {
        enabled = environment.getProperty("SBA_AUTH_ENABLED", Boolean.class, false);
        username = environment.getProperty("SBA_AUTH_USERNAME", "blackbox");
        password = environment.getProperty("SBA_AUTH_PASSWORD", "");
        apiToken = environment.getProperty("SBA_AUTH_API_TOKEN", "");
        secureCookies = environment.getProperty("SBA_AUTH_SECURE_COOKIES", Boolean.class, true);
        if (enabled) {
            if (username.isBlank()) {
                throw new IllegalStateException("SBA_AUTH_USERNAME must not be blank when authentication is enabled");
            }
            validateSecret("SBA_AUTH_PASSWORD", password);
            validateSecret("SBA_AUTH_API_TOKEN", apiToken);
            if (password.equals(apiToken)) {
                throw new IllegalStateException("Browser password and agent API token must be different secrets");
            }
        }
    }

    private static void validateSecret(String name, String value) {
        if (value.length() < 32 || value.isBlank() || value.chars().distinct().count() < 8
                || value.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalStateException(name + " must be an independently generated secret of at least 32 characters without whitespace");
        }
    }

    boolean enabled() { return enabled; }
    String username() { return username; }
    String password() { return password; }
    String apiToken() { return apiToken; }
    boolean secureCookies() { return secureCookies; }
}
