package dev.nathan.sbaagentic.memory.internal.domain;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Whole-reply validation: one failure rejects the reply. A reply must be non-blank, complete
 * ({@code finish_reason} {@code stop}) and within the output bound, cite at least one handle, cite
 * only known handles, and mention no event ID outside the evidence it was given.
 */
public final class ContinuationSynthesisValidator {

    public static final String EMPTY = "empty";
    public static final String TRUNCATED = "truncated";
    public static final String INVALID_REFERENCE = "invalid_reference";

    private static final Pattern CITATION = Pattern.compile("\\[(E\\d+(?:\\s*,\\s*E\\d+)*)\\]");
    private static final Pattern UUID = Pattern.compile(
            "(?<![0-9a-f-])[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}(?![0-9a-f-])",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern EVENT_REF =
            Pattern.compile("\\bevent:\\s*([0-9a-f][0-9a-f-]{7,35})", Pattern.CASE_INSENSITIVE);

    private ContinuationSynthesisValidator() {}

    public static Verdict validate(
            String content, String finishReason, List<ContinuationHandle> handles, int maxChars) {
        String text = content == null ? "" : content.strip();
        if (text.isEmpty()) {

            return Verdict.rejected(EMPTY);
        }
        if (!"stop".equals(finishReason) || text.length() > maxChars) {

            return Verdict.rejected(TRUNCATED);
        }
        Map<String, ContinuationHandle> byHandle = new HashMap<>();
        Set<String> knownEvents = new HashSet<>();
        for (ContinuationHandle handle : handles) {
            byHandle.put(handle.handle(), handle);
            knownEvents.add(handle.eventId().toLowerCase(Locale.ROOT));
        }
        Map<String, ContinuationHandle> cited = new LinkedHashMap<>();
        Matcher citation = CITATION.matcher(text);
        while (citation.find()) {
            for (String token : citation.group(1).split(",")) {
                ContinuationHandle handle = byHandle.get(token.strip());
                if (handle == null) {

                    return Verdict.rejected(INVALID_REFERENCE);
                }
                cited.putIfAbsent(handle.handle(), handle);
            }
        }
        if (cited.isEmpty()) {

            return Verdict.rejected(INVALID_REFERENCE);
        }
        Matcher uuid = UUID.matcher(text);
        while (uuid.find()) {
            if (!knownEvents.contains(uuid.group().toLowerCase(Locale.ROOT))) {

                return Verdict.rejected(INVALID_REFERENCE);
            }
        }
        Matcher event = EVENT_REF.matcher(text);
        while (event.find()) {
            if (!knownEvents.contains(event.group(1).toLowerCase(Locale.ROOT))) {

                return Verdict.rejected(INVALID_REFERENCE);
            }
        }

        return new Verdict(true, null, text, List.copyOf(cited.values()));
    }

    /** {@code citations} are the cited handles in first-mention order; empty when rejected. */
    public record Verdict(boolean valid, String reason, String text, List<ContinuationHandle> citations) {

        static Verdict rejected(String reason) {

            return new Verdict(false, reason, null, List.of());
        }
    }
}
