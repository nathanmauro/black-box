package dev.nathan.sbaagentic.memory.internal.adapter.in.mcp;

import dev.nathan.sbaagentic.memory.RecallResult;
import dev.nathan.sbaagentic.memory.RecalledItem;
import java.util.ArrayList;
import java.util.List;

final class RecallResultClamp {

    static final int DEFAULT_MAX_CHARS = 24_000;
    static final int MIN_MAX_CHARS = 500;

    private static final int ITEM_OVERHEAD_CHARS = 200;
    private static final int MIN_TRIMMED_FIELD_CHARS = 80;

    private RecallResultClamp() {}

    static int normalizeMaxChars(Integer maxChars) {
        if (maxChars == null || maxChars <= 0) {

            return DEFAULT_MAX_CHARS;
        }

        return Math.max(MIN_MAX_CHARS, maxChars);
    }

    static RecallResult clamp(RecallResult result, int maxChars) {
        if (result == null) {

            return null;
        }
        List<RecalledItem> originalItems = result.items() == null ? List.of() : result.items();
        List<RecalledItem> kept = new ArrayList<>();
        long total = 0;
        boolean truncated = result.truncated();
        for (int i = 0; i < originalItems.size(); i++) {
            RecalledItem item = originalItems.get(i);
            long itemCost = itemCost(item);
            if (total + itemCost <= maxChars) {
                kept.add(item);
                total += itemCost;
                continue;
            }

            truncated = true;
            long remaining = maxChars - total;
            if (remaining * 4 >= maxChars) {
                TrimmedItem trimmed = trimToFit(item, remaining);
                if (trimmed != null) {
                    kept.add(trimmed.item());
                }
            }
            break;
        }

        return new RecallResult(
                result.scope(),
                result.withinHours(),
                result.kinds(),
                kept.size(),
                List.copyOf(kept),
                result.mode(),
                truncated);
    }

    static long cost(RecallResult result) {
        if (result == null || result.items() == null) {

            return 0;
        }

        return result.items().stream().mapToLong(RecallResultClamp::itemCost).sum();
    }

    private static TrimmedItem trimToFit(RecalledItem item, long maxItemCost) {
        if (item == null) {

            return null;
        }
        RecalledItem candidate = item;
        long cost = itemCost(candidate);
        boolean trimmed = false;

        TrimmedField rationale = trimField(candidate.rationale(), cost - maxItemCost);
        if (rationale != null) {
            candidate = withText(candidate, candidate.headline(), rationale.value());
            cost -= rationale.savedChars();
            trimmed = true;
        }
        if (cost <= maxItemCost && trimmed) {

            return new TrimmedItem(candidate);
        }

        TrimmedField headline = trimField(candidate.headline(), cost - maxItemCost);
        if (headline != null) {
            candidate = withText(candidate, headline.value(), candidate.rationale());
            cost -= headline.savedChars();
            trimmed = true;
        }
        if (cost <= maxItemCost && trimmed) {

            return new TrimmedItem(candidate);
        }

        return null;
    }

    private static TrimmedField trimField(String value, long requiredSavings) {
        if (value == null || value.length() <= MIN_TRIMMED_FIELD_CHARS || requiredSavings <= 0) {

            return null;
        }

        int chosenKeep = -1;
        for (int keep = value.length() - 1; keep >= MIN_TRIMMED_FIELD_CHARS; keep--) {
            int savings = savings(value.length(), keep);
            if (savings >= requiredSavings) {
                chosenKeep = keep;
                break;
            }
        }
        if (chosenKeep == -1) {
            chosenKeep = MIN_TRIMMED_FIELD_CHARS;
        }

        int savedChars = savings(value.length(), chosenKeep);
        if (savedChars <= 0) {

            return null;
        }
        int removedChars = value.length() - chosenKeep;
        String suffix = suffix(removedChars);

        return new TrimmedField(value.substring(0, chosenKeep) + suffix, savedChars);
    }

    private static int savings(int originalLength, int keep) {
        int removedChars = originalLength - keep;

        return originalLength - keep - suffix(removedChars).length();
    }

    private static String suffix(int removedChars) {

        return "… (+" + removedChars + " chars)";
    }

    private static long itemCost(RecalledItem item) {
        if (item == null) {

            return ITEM_OVERHEAD_CHARS;
        }

        return ITEM_OVERHEAD_CHARS
                + length(item.headline())
                + length(item.rationale())
                + length(item.nextAction())
                + length(item.toAgent())
                + joinedLength(item.alternatives())
                + joinedLength(item.openLoops());
    }

    private static int length(String value) {

        return value == null ? 0 : value.length();
    }

    private static int joinedLength(List<String> values) {
        if (values == null || values.isEmpty()) {

            return 0;
        }
        int length = 0;
        boolean afterFirst = false;
        for (String value : values) {
            if (value == null) {
                continue;
            }
            if (afterFirst) {
                length++;
            }
            length += value.length();
            afterFirst = true;
        }

        return length;
    }

    private static RecalledItem withText(RecalledItem item, String headline, String rationale) {

        return new RecalledItem(
                item.eventId(),
                item.sessionId(),
                item.kind(),
                item.source(),
                item.clientSessionId(),
                item.repo(),
                item.observedAt(),
                headline,
                rationale,
                item.alternatives(),
                item.confidence(),
                item.openLoops(),
                item.nextAction(),
                item.toAgent(),
                item.score());
    }

    private record TrimmedField(String value, int savedChars) {}

    private record TrimmedItem(RecalledItem item) {}
}
