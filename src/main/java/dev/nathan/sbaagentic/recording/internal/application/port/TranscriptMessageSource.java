package dev.nathan.sbaagentic.recording.internal.application.port;

import java.util.List;

import dev.nathan.sbaagentic.recording.AgentSession;

public interface TranscriptMessageSource {

    TranscriptRead read(AgentSession session, List<String> candidatePaths);
}
