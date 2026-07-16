package dev.nathan.sbaagentic.runner.engine;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public final class RateLimitDetector {

    private static final List<String> PHRASES = List.of("rate limit", "usage limit", "quota");
    private static final Pattern STATUS_429 = Pattern.compile(
            "(?i)(?:\\b(?:http|status|error|failed|failure)\\b.{0,40}\\b429\\b"
                    + "|\\b429\\b.{0,40}\\b(?:too many requests|rate limit(?:ed)?)\\b)");

    private RateLimitDetector() {
    }

    public static boolean matches(String paneText) {
        if (paneText == null || paneText.isBlank()) {
            return false;
        }
        String normalized = paneText.toLowerCase(Locale.ROOT);
        return PHRASES.stream().anyMatch(normalized::contains)
                || STATUS_429.matcher(paneText).find();
    }
}
