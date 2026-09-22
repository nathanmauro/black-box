package dev.nathan.sbaagentic.judgment.internal.domain;

import java.time.Instant;
import java.util.List;

public record Beat(
        String id,
        String sessionId,
        Instant startedAt,
        Instant endedAt,
        List<BeatEvent> events,
        List<String> lines,
        String text) {

    public List<String> eventIds() {

        return events.stream().map(BeatEvent::id).toList();
    }

    public String title() {
        String line = lines.isEmpty() ? "" : lines.get(0);

        return line.length() <= 72 ? line : line.substring(0, 69) + "...";
    }
}
