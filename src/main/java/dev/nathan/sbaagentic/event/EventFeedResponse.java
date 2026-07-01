package dev.nathan.sbaagentic.event;

import java.util.List;

public record EventFeedResponse(
        int limit,
        long count,
        List<EventFeedItem> items,
        String nextBefore) {
}
