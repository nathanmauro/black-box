package dev.nathan.sbaagentic.runner.engine;

import java.util.List;
import java.util.Locale;

public final class RateLimitDetector {

    private static final List<String> PATTERNS = List.of("429", "rate limit", "usage limit", "quota");

    private RateLimitDetector() {
    }

    public static boolean matches(String paneText) {
        if (paneText == null || paneText.isBlank()) {
            return false;
        }
        String normalized = paneText.toLowerCase(Locale.ROOT);
        return PATTERNS.stream().anyMatch(normalized::contains);
    }
}
