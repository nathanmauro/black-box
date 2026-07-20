package dev.nathan.sbaagentic.recording;

import java.util.List;

public record EventFeedResponse(
        int limit,
        long count,
        List<EventFeedItem> items,
        String nextBefore) {
}
