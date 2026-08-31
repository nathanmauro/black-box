package dev.nathan.sbaagentic.recording.internal.application.port;

import java.util.List;

import dev.nathan.sbaagentic.recording.AgentEvent;

public record TranscriptRead(
        boolean available,
        boolean complete,
        String reason,
        List<AgentEvent> messages) {

    public static TranscriptRead unavailable(String reason) {
        return new TranscriptRead(false, false, reason, List.of());
    }
}
