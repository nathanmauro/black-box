package dev.nathan.sbaagentic.memory;

import java.util.List;
import java.util.Map;

/** Bounded discovery only. Canonical text remains available through the recorded event API. */
public record CompactSearchResult(
        String status,
        String query,
        int count,
        List<Hit> items,
        Map<String, Object> appliedFilters,
        Map<String, Coverage> coverage,
        boolean truncated,
        int omittedItems,
        int maxBytes,
        List<String> diagnostics) {

    public record Hit(
            String eventId,
            String sessionId,
            String clientSessionId,
            String source,
            String eventType,
            String role,
            String observedAt,
            String excerpt,
            boolean excerptTruncated,
            List<String> backends,
            String provenance,
            SourceReference sourceReference,
            List<String> similarEventIds,
            int similarCount,
            boolean similarMembersTruncated) {}

    public record SourceReference(
            String status,
            String eventPath,
            String browsePath,
            String externalProvider,
            String externalTaskId,
            String externalStatus) {}

    public record Coverage(String status, int candidates, boolean candidateLimitReached) {}
}
