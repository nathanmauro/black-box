package dev.nathan.sbaagentic.memory.internal.application;

import dev.nathan.sbaagentic.recording.CaptureIdeaRequest;
import java.util.List;

/**
 * Result of migrating {@code [Idea]} observations. A dry run ({@code apply=false}) writes nothing
 * and reports what would be captured; {@code created} counts ideas captured by this call and
 * {@code skipped} counts candidates not captured (already migrated, or missing a title).
 */
public record IdeaMigrationResult(boolean apply, List<Candidate> candidates, int created, int skipped) {

    public record Candidate(
            String observationId,
            String sessionId,
            CaptureIdeaRequest idea,
            List<String> warnings,
            boolean alreadyMigrated,
            String createdEventId) {}
}
