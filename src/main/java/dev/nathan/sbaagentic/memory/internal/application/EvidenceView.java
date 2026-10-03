package dev.nathan.sbaagentic.memory.internal.application;

import dev.nathan.sbaagentic.recording.LaneListing;
import java.time.Instant;
import java.util.List;

/** Read model for one Evidence event. */
public record EvidenceView(
        String eventId,
        String sessionId,
        String source,
        String clientSessionId,
        String repo,
        String claim,
        String excerpt,
        String sourceRef,
        String outputDigest,
        Instant observedAt,
        String capturedBy,
        List<String> supports,
        List<String> refutes,
        String notes,
        String project,
        List<LaneListing> alsoIn,
        Instant capturedAt) {}
