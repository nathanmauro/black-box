package dev.nathan.sbaagentic.recording.internal.application.port;

import dev.nathan.sbaagentic.recording.AgentEvent;
import java.util.List;

public record TranscriptRead(boolean available, boolean complete, String reason, List<AgentEvent> messages) {

    public static TranscriptRead unavailable(String reason) {

        return new TranscriptRead(false, false, reason, List.of());
    }
}
