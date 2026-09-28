package dev.nathan.sbaagentic.recording;

public interface SessionTranscriptOperations {

    default SessionTranscriptResponse transcript(String sessionId, String query, String before, int limit) {

        return transcript(sessionId, query, before, limit, false);
    }

    /**
     * {@code humanOnly} returns only recorded human turns; messages read back from the client's own
     * transcript file are not classified and are left out.
     */
    SessionTranscriptResponse transcript(String sessionId, String query, String before, int limit, boolean humanOnly);
}
