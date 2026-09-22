package dev.nathan.sbaagentic.memory;

import java.util.List;

public interface MemoryRecallOperations {

    /**
     * Recalls with the default result count. Retained so existing callers keep compiling.
     */
    default RecallResult recall(String scope, int withinHours, List<String> kinds) {

        return recall(scope, withinHours, kinds, null);
    }

    /**
     * Recalls prior intent. {@code limit} bounds how many items come back; omit it for the default.
     *
     * <p>The bound matters more than it looks. Semantic recall always produces candidates, so an
     * unbounded recall returns a full page of full-text items on every call — enough to crowd out
     * the caller's own context, which is the opposite of what recalling context is for.
     */
    RecallResult recall(String scope, int withinHours, List<String> kinds, Integer limit);
}
