package dev.nathan.sbaagentic.recording;

public interface SessionTranscriptOperations {

    SessionTranscriptResponse transcript(String sessionId, String query, String before, int limit);
}
