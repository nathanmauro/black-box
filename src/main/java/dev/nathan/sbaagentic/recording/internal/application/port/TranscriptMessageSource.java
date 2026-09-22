package dev.nathan.sbaagentic.recording.internal.application.port;

import dev.nathan.sbaagentic.recording.AgentSession;
import java.util.List;

public interface TranscriptMessageSource {

    TranscriptRead read(AgentSession session, List<String> candidatePaths);
}
