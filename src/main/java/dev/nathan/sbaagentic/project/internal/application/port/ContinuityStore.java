package dev.nathan.sbaagentic.project.internal.application.port;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/** Read-only view of structured captures, sessions and saved braids for continuity checks. */
public interface ContinuityStore {

    /** Structured capture rows of the given event types, newest first, at most {@code limit}. */
    List<CaptureRow> captures(Collection<String> eventTypes, int limit);

    /** Total rows of the given event types, so callers can say when a scan stopped early. */
    long countCaptures(Collection<String> eventTypes);

    /** Sessions by id; ids with no row are absent from the result. */
    List<SessionRow> sessions(Collection<String> ids);

    /** Saved braids newest first, at most {@code limit}, each with its ordered inputs. */
    List<BraidRow> braids(int limit);

    long countBraids();

    /**
     * One capture. {@code text} is the stored body read up to a bounded prefix; {@code metadataJson}
     * is stored whole. Session columns are null when the session row is missing.
     */
    record CaptureRow(
            String eventId,
            String sessionId,
            String eventType,
            String metadataJson,
            String text,
            Instant observedAt,
            String sessionCwd) {}

    record SessionRow(String id, String source, String title, String cwd, Instant startedAt) {}

    record BraidRow(String id, String projectKey, String title, Instant createdAt, List<BraidInput> inputs) {}

    /** {@code sessionExists} is false when the input names a session that has no row. */
    record BraidInput(String sessionId, int order, boolean sessionExists) {}
}
