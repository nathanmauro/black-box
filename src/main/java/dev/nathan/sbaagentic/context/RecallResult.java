package dev.nathan.sbaagentic.context;

import java.util.List;

/**
 * The structured answer to "what did agents working on this already decide?" — the read side of the
 * write+query loop that is Black Box's reason to exist. {@code scope} echoes the repo-or-topic that
 * was matched, so a caller (human or agent) can see what the recall was anchored to.
 */
public record RecallResult(
        String scope,
        int withinHours,
        List<String> kinds,
        int count,
        List<RecalledItem> items) {
}
