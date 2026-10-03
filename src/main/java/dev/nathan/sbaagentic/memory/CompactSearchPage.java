package dev.nathan.sbaagentic.memory;

import java.util.List;
import java.util.Map;

/** One canonical keyset page; {@code nextBefore} advances from the last delivered hit only. */
public record CompactSearchPage(
        String status,
        String mode,
        int count,
        List<CompactSearchResult.Hit> items,
        Map<String, Object> appliedFilters,
        boolean hasMore,
        String nextBefore,
        boolean budgetLimited,
        boolean excerptsTruncated,
        int limit,
        int maxBytes,
        List<String> diagnostics) {}
