package dev.nathan.sbaagentic.memory;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

/**
 * One entry in a project's live broadcast set: something every concurrent or newly started session
 * in that project should know right now. {@code kind} is {@code hazard}, {@code invariant}, or
 * {@code tentacle}; {@code status} is {@code active}, {@code expired}, or {@code cleared} as of the
 * read; {@code stale} marks a tentacle that is past its follow-up or untouched past the threshold.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProjectBroadcast(
        String id,
        String projectScope,
        String key,
        String kind,
        String message,
        String severity,
        String source,
        String clientSessionId,
        Instant createdAt,
        Instant updatedAt,
        Instant expiresAt,
        Instant followUpAt,
        Instant clearedAt,
        String clearedBy,
        String clearReason,
        String status,
        boolean stale) {

    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_EXPIRED = "expired";
    public static final String STATUS_CLEARED = "cleared";
}
