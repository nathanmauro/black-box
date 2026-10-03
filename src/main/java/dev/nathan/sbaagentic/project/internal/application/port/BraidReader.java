package dev.nathan.sbaagentic.project.internal.application.port;

import java.time.Instant;
import java.util.List;

public interface BraidReader {
    List<Candidate> findBraids(
            String id, String query, String sessionId, int limit, Instant beforeTime, String beforeId);

    record Candidate(
            String id,
            String canonicalKey,
            String title,
            String body,
            String provider,
            String model,
            Instant createdAt,
            List<Source> sessions) {}

    record Source(String sessionId, String source, String clientSessionId, String cwd, String provenanceBasis) {}
}
