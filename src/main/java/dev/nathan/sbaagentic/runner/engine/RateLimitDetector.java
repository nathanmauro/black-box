package dev.nathan.sbaagentic.runner.engine;

import java.util.regex.Pattern;

public final class RateLimitDetector {

    // Anchored to exhaustion wording: informational text such as codex's
    // "You have 3 usage limit resets available" or a status line's
    // "weekly limit 90% left" must never count as a hit.
    private static final Pattern LIMIT_EXHAUSTED = Pattern.compile(
            "(?i)(?:\\b(?:rate|usage)\\s+limits?\\b[^\\n]{0,80}\\b(?:reached|exceeded|exhausted|hit)\\b"
                    + "|\\b(?:reached|exceeded|exhausted|hit)\\b[^\\n]{0,80}\\b(?:rate|usage)\\s+limits?\\b"
                    + "|\\bquota\\b[^\\n]{0,40}\\b(?:reached|exceeded|exhausted)\\b"
                    + "|\\b(?:out of|over)\\s+quota\\b"
                    + "|\\brate.?limited\\b"
                    + "|\\btoo many requests\\b)");
    private static final Pattern STATUS_429 = Pattern.compile(
            "(?i)(?:\\b(?:http|status|error|failed|failure)\\b.{0,40}\\b429\\b"
                    + "|\\b429\\b.{0,40}\\b(?:too many requests|rate limit(?:ed)?)\\b)");

    private RateLimitDetector() {
    }

    public static boolean matches(String paneText) {
        if (paneText == null || paneText.isBlank()) {
            return false;
        }
        return LIMIT_EXHAUSTED.matcher(paneText).find()
                || STATUS_429.matcher(paneText).find();
    }
}
